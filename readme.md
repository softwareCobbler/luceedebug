# luceedebug

luceedebug is a step debugger for Lucee.

There are two components:

- A Java agent
- A VS Code extension

The java agent needs a particular invocation and needs to be run as part of JVM/Lucee server startup.

The VS Code client extension is available as `luceedebug` when searching in the VS Code extensions pane (or it can be built locally, see subsequent instructions).

## Java Agent

### Build Agent Jar

The following steps will build to: `./luceedebug/build/libs/luceedebug.jar`

#### Build Agent Jar on Mac / Linux

```
cd luceedebug
./gradlew shadowjar
```

#### Build Agent Jar on Windows

```
cd luceedebug
gradlew.bat shadowjar
```

### Install and Configure Agent

Add the following to your java invocation. (Tomcat users can use the `setenv.sh` file for this purpose.)

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9999

-javaagent:/abspath/to/luceedebug.jar=jdwpHost=localhost,jdwpPort=9999,cfHost=0.0.0.0,cfPort=10000,jarPath=/abspath/to/luceedebug.jar
```

Most java devs will be familiar with the `agentlib` part. We need JDWP running, listening on a socket on localhost. luceedebug will attach to JDWP over a socket, running from within the same JVM. It stays attached for the life of the JVM.

Note that JDWP `address` and luceedebug's `jdwpHost`/`jdwpPort` must match.

The `cfPort` and `cfHost` options are the host/port that the VS Code debugger attaches to. Note that JDWP can usually listen on localhost (the connection to JDWP from luceedebug is a loopback connection).

If Lucee is running in a docker container, the `cfHost` must be `0.0.0.0`. However, be careful not to do this on a publicly-accessible, unprotected server, as you could expose the debugger to the public (which would be a major security vulnerability).

The `jarPath` argument is the absolute path to the luceedebug.jar file. Unfortunately we have to say its name twice! One tells the JVM which jar to use as a java agent, the second is an argument to the java agent about where to find the jar it will load debugging instrumentation from.

(There didn't seem to be an immediately obvious way to pull the name of "the current" jar file from an agent's `premain`, but maybe it's just been overlooked. If you know let us know!)

### VS Code luceedebug Debugger Extension

#### Install and Run from VS Code Marketplace

The VS Code luceedebug extension is available on the VS Code Marketplace. If you are an end-user who just wants to start debugging your CFML, install the luceedebug extension from the Marketplace.

##### Run the Extension

- Go to the "run and debug" menu (looks like a bug with a play button)
- Add a CFML debug configuration (if you haven't already--it only needs to be done once): Run > Open Configurations. (See the [configuration example, below](#vs-code-extension-configuration).)
- Prime the Java agent by warming up your application. (E.g., request its home page.) Note: This step may eventually be obsoleted by #13.
- Attach to the Lucee server
  - With a CFML file open, click the "Run and Debug" icon in the left menu.
  - In the select list labeled "Run and Debug," choose the name of the configuration you used in the `name` key of the debug configuration. (In the [configuration example, below](#vs-code-extension-configuration), it would be `Project A`.)
  - Click the green "play" icon next to the select list, above.
- General step debugging is documented [here](https://code.visualstudio.com/docs/editor/debugging), but the following is a synopsis.
  - With a CFML file open, click in the margin to the left of the line number, which will set a breakpoint (represented by a red dot).
  - Use your application in a way that would reach that line of code.
  - The application will pause execution at that line of code and allow you to inspect the current state.
  - The debug navigation buttons will allow you to continue execution or step into and out of functions, etc.

#### Hacking the luceedebug Extension

If you want to hack the extension, itself, build/run instructions follow.

##### Build Extension

Prerequisites:
* `npm`
* `typescript`
   * Mac: `brew install typescript`

```
# vs code client
cd vscode-client
npm install

npm run build-dev-windows # windows
npm run build-dev-linux # mac/linux
```

##### Run the Self-Built Extension

Steps to run the extension in VS Code's "extension development host":
- Open VS Code in this dir
  ```
  cd vscode-client
  code . # open vs code in this dir
  ```
- Go to the "run and debug" menu (looks like a bug with a play button)
- In the select list labeled "Run and Debug," choose the "Launch luceedebug in Extension Development Host" option and click the green "play" icon to launch.
- The extension development host window opens
- Load your Lucee project from that VS Code instance
- Continue on to [Run the Extension](#run-the-extension)


### VS Code Extension Configuration

A CFML debug configuration looks like:
```json
{
    "type": "cfml",
    "request": "attach",
    "name": "Project A",
    "hostName": "localhost",
    "port": 10000,
    // optional
    "pathTransforms": [
      {
        "idePrefix": "${workspaceFolder}",
        "cfPrefix": "/app"
      }
    ]
}
```
Hostname and port should match the `cfHost` and `cfPort` you've configured the java agent with.

#### Mapping Paths with `pathTransforms`

`pathTransforms` maps between "IDE paths" and "Lucee server paths". For example, in your editor, you may be working on a file called `/foo/bar/baz/TheThing.cfc`, but it runs in a container and Lucee sees it as `/serverAppRoot/bar/baz/TheThing.cfc`. To keep the IDE and Lucee talking about the same files, we need to know how to transform these path names.

Currently, it is a simple prefix replacement, e.g.:

```json
"pathTransforms": [
  {
    "idePrefix": "/foo",
    "cfPrefix": "/serverAppRoot"
  }
]
```

In the above example, the IDE would announce, "set a breakpoint in `/foo/bar/baz/TheThing.cfc`, which the server will understand as, "set a breakpoint in `/serverAppRoot/bar/baz/TheThing.cfc`".

Omitting `pathTransforms` means no path transformation will take place.

Multiple `pathTransforms`  may be specified if more than one mapping is needed. The first match wins.

Example:

```json
"pathTransforms": [
  {
    "idePrefix": "/Users/sc/projects/subapp_b_helper",
    "cfPrefix": "/var/www/subapp/b/helper"
  },
  {
    "idePrefix": "/Users/sc/projects/subapp_b",
    "cfPrefix": "/var/www/subapp/b"
  },
  {
    "idePrefix": "/Users/sc/projects/app",
    "cfPrefix": "/var/www"
  }
]
```

In this example:

* A breakpoint set on `/Users/sc/projects/app/Application.cfc` will match the last transform and map to `/var/www/Application.cfc` on the server.
* A breakpoint set on `/var/www/subapp/b/helper/HelpUtil.cfc` will match the first transform and map to `/var/www/subapp/b/helper/HelpUtil.cfc` on the server.