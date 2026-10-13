# soderer-applicationbase

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)
[![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.org/)

**Self-updating base for Java desktop and console applications.**

`soderer-applicationbase` lets an application check for new versions, download them, verify their signature and
replace its own jar file — with the user interaction handled either by Swing dialogs or on the console. In addition,
it provides a ready-made configuration dialog for Swing applications, including language selection, proxy settings,
desktop link creation and an update button.

---

## Features

| | |
|---|---|
| **Self update** | Checks a version index, downloads the new jar, verifies its signature and replaces the running application |
| **Restart** | Optionally restarts the application with the same JVM and application parameters after the update |
| **Version index sources** | JSON or XML index on your own server, GitHub "latest release" API, any http(s) URL with the JSON format, local `file://` JSON |
| **Signature check** | The update is only installed if its jar signature is trusted by one of the given CA certificates |
| **Proxy support** | Direct connection, system proxy, proxy URL, WPAD or PAC script |
| **Credentials** | Download URLs may contain `<username>` and `<password>`, which are asked for interactively |
| **Swing base class** | `UpdateableGuiApplication` with update dialogs, progress display and a password protected keystore for credentials |
| **Console base class** | `UpdateableConsoleApplication` with console questions, password input and a progress bar |
| **Configuration dialog** | `ApplicationConfigurationDialog` to edit the application configuration, change the language, create a desktop link and check for updates |

## Requirements

- Java 17 or newer
- [soderer-utilities](https://github.com/hudeany/Utilities), json, network, proxyautoconfig and csv
- For GUI applications: [soderer-swing](https://github.com/hudeany/Swing)
- For console applications: [soderer-console](https://github.com/hudeany/Console)

soderer-swing and soderer-console are optional dependencies: add the one your application uses.

## Installation

### Maven

```xml
<dependency>
	<groupId>de.soderer</groupId>
	<artifactId>soderer-applicationbase</artifactId>
	<version>VERSION</version>
</dependency>

<!-- GUI applications -->
<dependency>
	<groupId>de.soderer</groupId>
	<artifactId>soderer-swing</artifactId>
	<version>VERSION</version>
</dependency>

<!-- or console applications -->
<dependency>
	<groupId>de.soderer</groupId>
	<artifactId>soderer-console</artifactId>
	<version>VERSION</version>
</dependency>
```

### Gradle

```groovy
implementation "de.soderer:soderer-applicationbase:VERSION"
implementation "de.soderer:soderer-swing:VERSION"     // GUI applications
implementation "de.soderer:soderer-console:VERSION"   // or console applications
```

## Usage

### GUI application

Derive the main window from `UpdateableGuiApplication`. It is a `JFrame` that implements all callbacks of the
update process with Swing dialogs and keeps credentials in a password protected keystore file.

```java
public class MyApplication extends UpdateableGuiApplication {
	public static final String APPLICATION_NAME = "MyApplication";
	public static final Version VERSION = new Version("1.2.0");

	public MyApplication() throws Exception {
		super(APPLICATION_NAME, VERSION, new File(System.getProperty("user.home"), ".MyApplication.keystore"));
		// build the user interface ...
	}

	private void checkForUpdate() throws Exception {
		ApplicationUpdateUtilities.executeUpdate(
				this,                                                      // update parent (dialogs)
				"https://api.github.com/repos/me/MyApplication/releases/latest",
				null,                                                      // proxy configuration, null for direct
				APPLICATION_NAME,
				VERSION,
				"MyUpdateCa.crt",                                          // trusted CA certificate(s) in the classpath
				null, null,                                                // username / password for the download URL
				List.of(),                                                 // application parameters for the restart
				true,                                                      // restart after the update
				false);                                                    // no unsigned updates
	}
}
```

### Console application

```java
UpdateableConsoleApplication updateParent = new UpdateableConsoleApplication("MyTool", new Version("1.2.0"));
ApplicationUpdateUtilities.executeUpdate(updateParent, versionIndexUrl, null, "MyTool", new Version("1.2.0"),
		"MyUpdateCa.crt", null, null, List.of(args), true, false);
```

The user is asked on the console whether to install the update (answers starting with `y`, `j`, `o` or `s`
are accepted, so the question works in English, German, French, Spanish and Italian).

### Non-interactive update

For services and scheduled tasks without a user:

```java
ApplicationUpdateUtilities.updateNonInteractiveApplication(MyService.class, updateFileUrl, null,
		"MyUpdateCa.crt", null, null, jvmParameters, appParameters, true);
```

### Only checking for a new version

```java
Tuple<Version, String> newVersion = ApplicationUpdateUtilities.checkForNewVersionAvailable(
		versionIndexUrl, null, "MyApplication", currentVersion);

if (newVersion != null) {
	System.out.println("Version " + newVersion.getFirst() + " is available at " + newVersion.getSecond());
}
```

## Version index

The version index location may contain these placeholders:

| Placeholder | Replaced by |
|---|---|
| `<system>` | `windows` or `linux` |
| `<bitmode>` | `32` or `64` |
| `<username>` | username, asked for interactively |
| `<password>` | password, asked for interactively |
| `<time_seconds>` | current timestamp in seconds |

The JSON format contains one entry per application:

```json
{
	"MyApplication": {
		"version": "1.2.0",
		"downloadUrl": "https://example.com/download/MyApplication-1.2.0.jar"
	}
}
```

With the GitHub "latest release" API (`https://api.github.com/repos/<owner>/<repo>/releases/latest`), the
release name must start with the application name, the release tag is used as version and the application's
`.jar` asset as download (sources and javadoc jars are skipped).

## Signed updates

An update is only installed if the downloaded jar is signed and its signature chains up to one of the trusted CA
certificates. The certificates are loaded as resources from the application's classpath, several file names are
separated by commas. Unsigned updates are only accepted if no CA certificates are given **and**
`allowUnsafeUpdates` is `true` — which is not recommended.

## Configuration dialog

`ApplicationConfigurationDialog` shows all values of the application's `ConfigurationProperties` and lets the user
change them. Values with a list of allowed values are shown as drop-down, and it offers:

- language selection (from the available `LanguageProperties_*.properties` files)
- proxy configuration (none, system, proxy URL, WPAD, PAC URL)
- creation of a desktop link (Windows and Linux)
- an update check with installation of a new version
- display of system, runtime and Swing properties

## Building

The project is built with [Apache Ant](https://ant.apache.org/). Dependencies are downloaded automatically.

```sh
ant build_jar
```

This compiles the sources, runs the tests and creates the following files in `build/`:

- `soderer-applicationbase-VERSION.jar`
- `soderer-applicationbase-VERSION-sources.jar`
- `soderer-applicationbase-VERSION-javadoc.jar`

The version is read from the file `build.version`.

## License

Released under the [MIT License](LICENSE.txt).
