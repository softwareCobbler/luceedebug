## luceedebug

luceedebug is a step debugger for Lucee.

There are two components:

- A Java agent
- A VS Code plugin

The java agent needs a particular invocation and needs to be run as part of JVM/CF server startup.

The VS Code client plugin is not released on the VS Code plugin Marketplace, so it's easiest to run it locally, in an 'extension development host'.

### Java Agent

#### Build Agent Jar

```
# java agent
cd luceedebug
gradle shadowjar
ls ./luceedebug/build/libs/luceedebug.jar
```

#### Install and Configure Agent

Add the following to your java invocation. (Tomcat users can use the `setenv.sh` file for this purpose.)

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9999

-javaagent:/abspath/to/luceedebug.jar=jdwpHost=localhost,jdwpPort=9999,cfHost=0.0.0.0,cfPort=10000,jarPath=/abspath/to/luceedebug.jar
```

Most java devs will be familiar with the `agentlib` part. We need JDWP running, listening on a socket on localhost. luceedebug will attach to JDWP over a socket, running from within the same JVM. It stays attached for the life of the JVM.

Note that JDWP `address` and luceedebug's `jdwpHost`/`jdwpPort` must match.

The `cfPort` and `cfHost` options are the host/port that the VS Code debugger attaches to. Note that JDWP can usually listen on localhost (the connection to JDWP from luceedebug is a loopback connection), but if your server is in a docker container, the cfHost might have to be something that will listen to external connections coming into the container (e.g. 0.0.0.0 or etc. Don't do that in production.).

The `jarPath` argument is the absolute path to the luceedebug.jar file. Unfortunately we have to say its name twice! One tells the JVM which jar to use as a java agent, the second is an argument to the java agent about where to find the jar it will load debugging instrumentation from.

(There didn't seem to be an immediately obvious way to pull the name of "the current" jar file from an agent's `premain`, but maybe it's just been overlooked. If you know let us know!)

### VS Code Client

#### Build Client

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

#### Run Client

```
cd vscode-client
code . # open vs code in this dir
```

Steps to run the extension in VS Code's "extension development host":
- Open VS Code in this dir
- Go to the "run and debug" menu
- (looks like a bug with a play button)
- Run the "extension" configuration
- The extension development host window opens
- Load your CF project from that VS Code instance
- Add a CFML debug configuration (Run > Open Configurations)
- Attach to the CF server
- Set breakpoints, etc.

### Plugin options

A CFML debug configuration looks like:
```
{
    "type": "cfml",
    "request": "attach",
    "name": "Attach to server",
    "hostName": "localhost",
    "port": 8000,
    "pathTransform": { // optional
        "idePrefix": "${workspaceFolder}",
        "cfPrefix": "/app"
    }
}
```
Hostname and port should match the `cfHost` and `cfPort` you've configured the java agent with.

`pathTransform` maps between "IDE paths" and "CF server paths". For example, in your editor, you may be working on a file called `/foo/bar/baz/TheThing.cfc`, but it runs in a container and Lucee sees it as `/serverAppRoot/bar/baz/TheThing.cfc`. To keep the IDE and Lucee talking about the same files, we need to know how to transform these path names.

Currently, it is a simple prefix replacement, e.g.:

```
"pathTransform": {
    "idePrefix": "/foo",
    "cfPrefix": "/serverAppRoot"
}

ide says
 "set a breakpoint in '/foo/bar/baz/TheThing.cfc'
server will understand it as
 "set a breakpoint in '/serverAppRoot/bar/baz/TheThing.cfc'

server says
 "hit a breakpoint in '/serverAppRoot/bar/baz/TheThing.cfc'
ide will understand it as
 "hit a breakpoint in '/foo/bar/baz/TheThing.cfc'"
```

Omitting a `pathTransform` means no path transformation will take place.
