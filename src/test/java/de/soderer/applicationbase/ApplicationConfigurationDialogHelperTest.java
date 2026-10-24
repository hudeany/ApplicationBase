package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Headless tests of the static helpers of {@link ApplicationConfigurationDialog}, which need no window.
 */
@SuppressWarnings("static-method")
public class ApplicationConfigurationDialogHelperTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static BufferedImage createIcon() {
		final BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		icon.setRGB(1, 1, 0xFFFF0000);
		return icon;
	}

	@Test
	public void testStaticHelpers() throws Exception {
		final Class<?> dialogClass = ApplicationConfigurationDialog.class;
		final Class<?>[] stringParameter = new Class<?>[] { String.class };

		assertEquals("simple", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "simple"));
		assertEquals("\"\"", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, ""));
		assertEquals("\"a b\"", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "a b"));
		assertEquals("\"a\\\"b\"", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "a\"b"));
		assertEquals("\"a\\\\\\\"b\"", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "a\\\"b"));
		assertEquals("\"C:\\My Path\\\\\"", TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "C:\\My Path\\"));
		assertEquals("\"a\\tb\"".replace("\\t", "\t"), TestHelper.invokeStatic(dialogClass, "quoteWindowsCommandLineArgument", stringParameter, "a\tb"));

		assertEquals("\"a b\"", TestHelper.invokeStatic(dialogClass, "quoteDesktopExecArgument", stringParameter, "a b"));
		assertEquals("\"\\\\$x \\\\\"q\\\\\" \\\\`c\\\\`\"",TestHelper.invokeStatic(dialogClass, "quoteDesktopExecArgument", stringParameter, "$x \"q\" `c`"));
		assertEquals("\"\\\\\\\\path\"", TestHelper.invokeStatic(dialogClass, "quoteDesktopExecArgument", stringParameter, "\\path"));
		assertEquals("\"100%%\"", TestHelper.invokeStatic(dialogClass, "quoteDesktopExecArgument", stringParameter, "100%"));

		assertEquals("\"a\"\"b c\"", TestHelper.invokeStatic(dialogClass, "toVbsString", stringParameter, "a\"b\r\nc"));

		assertEquals(Boolean.TRUE, TestHelper.invokeStatic(dialogClass, "isExcludedJvmArgument", stringParameter, "-agentlib:jdwp=transport=dt_socket"));
		assertEquals(Boolean.TRUE, TestHelper.invokeStatic(dialogClass, "isExcludedJvmArgument", stringParameter, "-javaagent:jacoco.jar"));
		assertEquals(Boolean.FALSE, TestHelper.invokeStatic(dialogClass, "isExcludedJvmArgument", stringParameter, "-Xmx1g"));

		@SuppressWarnings("unchecked")
		final List<String> jvmArguments = (List<String>) TestHelper.invokeStatic(dialogClass, "getJvmArgumentsForDesktopLink", new Class<?>[0]);
		for (final String jvmArgument : jvmArguments) {
			assertFalse(jvmArgument.startsWith("-javaagent:"), jvmArgument);
		}
	}

	@Test
	public void testToBufferedImage() throws Exception {
		final Class<?>[] parameterTypes = new Class<?>[] { Image.class };
		final BufferedImage bufferedImage = createIcon();
		assertSame(bufferedImage, TestHelper.invokeStatic(ApplicationConfigurationDialog.class, "toBufferedImage", parameterTypes, bufferedImage));

		final ByteArrayOutputStream pngData = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", pngData);
		final Image toolkitImage = Toolkit.getDefaultToolkit().createImage(pngData.toByteArray());
		final BufferedImage convertedImage = (BufferedImage) TestHelper.invokeStatic(ApplicationConfigurationDialog.class, "toBufferedImage", parameterTypes, toolkitImage);
		assertEquals(16, convertedImage.getWidth());
		assertEquals(16, convertedImage.getHeight());
	}

	@Test
	public void testSwingProperties() throws Exception {
		@SuppressWarnings("unchecked")
		final Map<String, String> properties = (Map<String, String>) TestHelper.invokeStatic(ApplicationConfigurationDialog.class, "getSwingProperties", new Class<?>[0]);
		assertEquals(Boolean.toString(GraphicsEnvironment.isHeadless()), properties.get("Headless"));
		assertTrue(properties.containsKey("LookAndFeel"));
		assertTrue(properties.containsKey("Toolkit"));
		assertTrue(properties.containsKey("ScreenResolution (dpi)"));
		assertEquals(!GraphicsEnvironment.isHeadless(), properties.containsKey("Screens"));
	}
}
