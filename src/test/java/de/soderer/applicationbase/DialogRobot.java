package de.soderer.applicationbase;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Operates modal dialogs in GUI tests. The test thread opens a modal dialog (and blocks), while the robot thread
 * waits for the expected dialogs one after another and operates them on the event dispatch thread.
 *
 * <p>
 * After all steps are done (or if a step times out), every further dialog showing up is closed and reported as
 * unexpected, so a test can never hang on a modal dialog.
 * </p>
 */
public class DialogRobot implements AutoCloseable {
	private static final long STEP_TIMEOUT_MILLIS = 20_000;

	private static class Step {
		final String description;
		final Predicate<Window> windowMatcher;
		final Consumer<Window> action;
		final boolean includeHandledWindows;

		Step(final String description, final Predicate<Window> windowMatcher, final Consumer<Window> action) {
			this(description, windowMatcher, action, false);
		}

		Step(final String description, final Predicate<Window> windowMatcher, final Consumer<Window> action, final boolean includeHandledWindows) {
			this.description = description;
			this.windowMatcher = windowMatcher;
			this.action = action;
			this.includeHandledWindows = includeHandledWindows;
		}
	}

	/**
	 * Collects the steps of a robot before it is started. Not closeable itself, so fluent configuration causes no
	 * resource leak warnings; only the robot returned by {@link #start()} must be closed.
	 */
	public static class Steps {
		private final List<Step> steps = new ArrayList<>();

		/**
		 * Adds a step for the next showing window of a type.
		 */
		public Steps on(final Class<? extends Window> windowType, final Consumer<Window> action) {
			steps.add(new Step(windowType.getSimpleName(), windowType::isInstance, action));
			return this;
		}

		/**
		 * Adds a step for a showing window of a type, which may already have been operated by a previous step (e.g. a
		 * dialog which stays open after a nested dialog was closed). The step waits until no other (nested) dialog is
		 * showing anymore.
		 */
		public Steps onAgain(final Class<? extends Window> windowType, final Consumer<Window> action) {
			steps.add(new Step(windowType.getSimpleName() + " (again)", window -> windowType.isInstance(window) && !isAnyOtherDialogShowing(window), action, true));
			return this;
		}

		/**
		 * Adds a step for the next showing message or confirm dialog of {@link JOptionPane}.
		 */
		public Steps onOptionPane(final Consumer<Window> action) {
			steps.add(new Step("JOptionPane", window -> findComponent(window, JOptionPane.class) != null, action));
			return this;
		}

		/**
		 * Adds a step for the next showing dialog of {@link JFileChooser}.
		 */
		public Steps onFileChooser(final Consumer<Window> action) {
			steps.add(new Step("JFileChooser", window -> findComponent(window, JFileChooser.class) != null, action));
			return this;
		}

		/**
		 * Starts the robot thread with the collected steps.
		 */
		public DialogRobot start() {
			final DialogRobot robot = new DialogRobot(new ArrayList<>(steps));
			robot.robotThread = new Thread(robot::run, "DialogRobot");
			robot.robotThread.setDaemon(true);
			robot.robotThread.start();
			return robot;
		}
	}

	/**
	 * Creates a builder for the steps of a new robot.
	 */
	public static Steps steps() {
		return new Steps();
	}

	private final List<Step> steps;
	private final Set<Window> handledWindows = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
	private volatile boolean stopped = false;
	private volatile int finishedSteps = 0;
	private Thread robotThread;

	/**
	 * Checks whether GUI tests are enabled and can run (display available).
	 */
	public static boolean isGuiAvailable() {
		TestHelper.configureHeadless();
		return TestHelper.isGuiTestsEnabled() && !GraphicsEnvironment.isHeadless();
	}

	/**
	 * Message for skipped GUI tests.
	 */
	public static final String GUI_TESTS_DISABLED_MESSAGE = "GUI tests are disabled (tests run headless), enable them with -D" + TestHelper.GUI_TESTS_PROPERTY + "=true on a system with a display";

	private DialogRobot(final List<Step> steps) {
		this.steps = steps;
	}

	private static boolean isAnyOtherDialogShowing(final Window window) {
		for (final Window otherWindow : Window.getWindows()) {
			if (otherWindow != window && otherWindow instanceof JDialog && otherWindow.isShowing()) {
				return true;
			}
		}
		return false;
	}

	private void run() {
		for (final Step step : steps) {
			final Window window = waitForWindow(step.windowMatcher, STEP_TIMEOUT_MILLIS, step.includeHandledWindows);
			if (window == null) {
				if (!stopped) {
					problems.add("Timeout waiting for dialog: " + step.description + " (showing: " + getShowingDialogTitles() + ")");
					disposeAllDialogs();
				}
				break;
			}
			handledWindows.add(window);
			// Let the dialog finish showing: a dialog closed while setVisible(true) is still in progress may be shown again
			waitForEventQueue();
			try {
				SwingUtilities.invokeLater(() -> {
					try {
						step.action.accept(window);
					} catch (final Throwable e) {
						problems.add("Action on dialog " + step.description + " failed: " + e);
						window.dispose();
					}
				});
			} finally {
				finishedSteps++;
			}
		}

		// Watchdog: close all further dialogs, so the test thread is never blocked forever
		long lastActivityTime = System.currentTimeMillis();
		while (!stopped) {
			final Window window = waitForWindow(candidate -> candidate instanceof JDialog, 100, false);
			if (window != null) {
				handledWindows.add(window);
				problems.add("Unexpected dialog: " + window.getClass().getSimpleName() + " '" + ((JDialog) window).getTitle() + "'");
				SwingUtilities.invokeLater(window::dispose);
				lastActivityTime = System.currentTimeMillis();
			} else if (!isAnyDialogShowing()) {
				lastActivityTime = System.currentTimeMillis();
			} else if (System.currentTimeMillis() - lastActivityTime > STEP_TIMEOUT_MILLIS) {
				// An already operated dialog did not close (e.g. a clicked button had no effect)
				problems.add("Dialog still open after all steps were executed: " + getShowingDialogTitles());
				disposeAllDialogs();
				lastActivityTime = System.currentTimeMillis();
			}
		}
	}

	private static void waitForEventQueue() {
		try {
			Thread.sleep(300);
			SwingUtilities.invokeAndWait(() -> {
				// Only waits until all pending events are processed
			});
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Best effort only
		}
	}

	private static boolean isAnyDialogShowing() {
		for (final Window window : Window.getWindows()) {
			if (window instanceof JDialog && window.isShowing()) {
				return true;
			}
		}
		return false;
	}

	private static String getShowingDialogTitles() {
		final List<String> titles = new ArrayList<>();
		for (final Window window : Window.getWindows()) {
			if (window instanceof JDialog && window.isShowing()) {
				titles.add(window.getClass().getSimpleName() + " '" + ((JDialog) window).getTitle() + "'");
			}
		}
		return String.join(", ", titles);
	}

	private static void disposeAllDialogs() {
		SwingUtilities.invokeLater(() -> {
			for (final Window window : Window.getWindows()) {
				if (window instanceof JDialog && window.isShowing()) {
					window.dispose();
				}
			}
		});
	}

	private Window waitForWindow(final Predicate<Window> windowMatcher, final long timeoutMillis, final boolean includeHandledWindows) {
		final long endTime = System.currentTimeMillis() + timeoutMillis;
		while (!stopped && System.currentTimeMillis() < endTime) {
			for (final Window window : Window.getWindows()) {
				if (window.isShowing() && (includeHandledWindows || !handledWindows.contains(window)) && windowMatcher.test(window)) {
					return window;
				}
			}
			try {
				Thread.sleep(50);
			} catch (@SuppressWarnings("unused") final InterruptedException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Stops the robot and returns the problems found (unexpected dialogs, missing dialogs, failed actions).
	 */
	public List<String> stop() {
		stopped = true;
		if (robotThread != null) {
			try {
				robotThread.join(5_000);
			} catch (@SuppressWarnings("unused") final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		final List<String> result = new ArrayList<>(problems);
		if (finishedSteps < steps.size() && result.isEmpty()) {
			result.add("Only " + finishedSteps + " of " + steps.size() + " dialog steps were executed");
		}
		return result;
	}

	/**
	 * Stops the robot and throws an AssertionError, if there were problems.
	 */
	@Override
	public void close() {
		final List<String> result = stop();
		if (!result.isEmpty()) {
			throw new AssertionError(String.join("\n", result));
		}
	}

	// ===== Actions =====

	/**
	 * Closes the window like the window decoration would (window closing event).
	 */
	public static Consumer<Window> closeWindow() {
		return window -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
	}

	/**
	 * Disposes the window.
	 */
	public static Consumer<Window> dispose() {
		return Window::dispose;
	}

	/**
	 * Clicks the button with the given text.
	 */
	public static Consumer<Window> clickButton(final String buttonText) {
		return window -> {
			final AbstractButton button = findButton(window, buttonText);
			if (button == null) {
				throw new IllegalStateException("Button '" + buttonText + "' not found");
			}
			button.doClick();
		};
	}

	/**
	 * Clicks the default button of the dialog.
	 */
	public static Consumer<Window> clickDefaultButton() {
		return window -> {
			final JRootPane rootPane = ((JDialog) window).getRootPane();
			final JButton defaultButton = rootPane.getDefaultButton();
			if (defaultButton == null) {
				throw new IllegalStateException("No default button");
			}
			defaultButton.doClick();
		};
	}

	/**
	 * Answers a {@link JOptionPane} with a value (e.g. {@link JOptionPane#YES_OPTION}).
	 */
	public static Consumer<Window> answerOptionPane(final int value) {
		return window -> findComponent(window, JOptionPane.class).setValue(Integer.valueOf(value));
	}

	/**
	 * Enters a text into all password fields (password and confirmation).
	 */
	public static Consumer<Window> enterPassword(final String password) {
		return window -> {
			for (final JPasswordField passwordField : findComponents(window, JPasswordField.class)) {
				passwordField.setText(password);
			}
		};
	}

	/**
	 * Enters a text into the first editable text field, which is not a password field.
	 */
	public static Consumer<Window> enterText(final String text) {
		return window -> {
			for (final JTextField textField : findComponents(window, JTextField.class)) {
				if (!(textField instanceof JPasswordField) && textField.isEditable() && textField.isShowing()) {
					textField.setText(text);
					return;
				}
			}
			throw new IllegalStateException("No text field found");
		};
	}

	/**
	 * Sets all checkboxes to a status (by clicking them, so their action listeners are called).
	 */
	public static Consumer<Window> setCheckboxes(final boolean selected) {
		return window -> {
			for (final JCheckBox checkBox : findComponents(window, JCheckBox.class)) {
				if (checkBox.isSelected() != selected) {
					checkBox.doClick();
				}
			}
		};
	}

	/**
	 * Selects rows of the first table.
	 */
	public static Consumer<Window> selectTableRows(final int firstRow, final int lastRow) {
		return window -> findComponent(window, JTable.class).setRowSelectionInterval(firstRow, lastRow);
	}

	/**
	 * Selects the row of the first table, whose first column has the given value.
	 */
	public static Consumer<Window> selectTableRow(final String value) {
		return window -> {
			final JTable table = findComponent(window, JTable.class);
			final int row = findTableRow(table, value);
			table.setRowSelectionInterval(row, row);
		};
	}

	/**
	 * Double-clicks the row of the first table, whose first column has the given value.
	 */
	public static Consumer<Window> doubleClickTableRow(final String value) {
		return window -> doubleClickTableRow(findTableRow(findComponent(window, JTable.class), value)).accept(window);
	}

	private static int findTableRow(final JTable table, final String value) {
		for (int row = 0; row < table.getRowCount(); row++) {
			if (value.equals(table.getValueAt(row, 0))) {
				return row;
			}
		}
		throw new IllegalStateException("Table row '" + value + "' not found");
	}

	/**
	 * Double-clicks a row of the first table.
	 */
	public static Consumer<Window> doubleClickTableRow(final int row) {
		return window -> {
			final JTable table = findComponent(window, JTable.class);
			final Rectangle cell = table.getCellRect(row, 0, true);
			final Point point = new Point(cell.x + 2, cell.y + 2);
			table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, point.x, point.y, 2, false, MouseEvent.BUTTON1));
		};
	}

	/**
	 * Combines several actions.
	 */
	@SafeVarargs
	public static Consumer<Window> all(final Consumer<Window>... actions) {
		return window -> {
			for (final Consumer<Window> action : actions) {
				action.accept(window);
			}
		};
	}

	// ===== Component search =====

	public static AbstractButton findButton(final Container container, final String buttonText) {
		for (final AbstractButton button : findComponents(container, AbstractButton.class)) {
			if (buttonText.equals(button.getText())) {
				return button;
			}
		}
		return null;
	}

	public static <T extends Component> T findComponent(final Container container, final Class<T> componentType) {
		final List<T> components = findComponents(container, componentType);
		return components.isEmpty() ? null : components.get(0);
	}

	public static <T extends Component> List<T> findComponents(final Container container, final Class<T> componentType) {
		final List<T> result = new ArrayList<>();
		collectComponents(container, componentType, result);
		return result;
	}

	private static <T extends Component> void collectComponents(final Container container, final Class<T> componentType, final List<T> result) {
		for (final Component component : container.getComponents()) {
			if (componentType.isInstance(component)) {
				result.add(componentType.cast(component));
			}
			if (component instanceof Container) {
				collectComponents((Container) component, componentType, result);
			}
		}
	}
}
