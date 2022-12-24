import * as vscode from "vscode";

let currentDebugSession : vscode.DebugSession | null = null;

class CfDebugAdapter implements vscode.DebugAdapterDescriptorFactory {
	createDebugAdapterDescriptor(session: vscode.DebugSession, _executable: vscode.DebugAdapterExecutable | undefined): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
		currentDebugSession = session;
		
		const host = session.configuration.hostName;
		const port = parseInt(session.configuration.port);

		return new vscode.DebugAdapterServer(port, host);
	}
	
}

export function activate(context: vscode.ExtensionContext) {
	const outputChannel = vscode.window.createOutputChannel("lucee-debugger");
	context.subscriptions.push(outputChannel);
	context.subscriptions.push(vscode.debug.registerDebugAdapterDescriptorFactory("cfml", new CfDebugAdapter()));

	// is there an official type for this?
	// this is just gleaned from observed runtime behavior on dev machine
	interface DebugPaneContextMenuArgs {
		container: {
			expensive: boolean,
			name: string,
			variablesReference: number
		},
		sessionId: string,
		variable: {
			name: string,
			value: string,
			variablesReference: number
		}
	}

	context.subscriptions.push(
		vscode.commands.registerCommand("luceedebug.dump", async (args?: Partial<DebugPaneContextMenuArgs>) => {
			if (args?.variable === undefined) {
				// this could be called from the command pallette (press F1 and type it)
				// rather than from the debug variables pane context menu. Maybe there is a better
				// way to determine where this was called from, or prevent it from being called anywhere
				// except the debug variables pane context menu.
				return;
			}
			// need a timeout, or does this cb get wrapped in a timeout by whoever we're passing it to 
			await currentDebugSession?.customRequest("dump", {variablesReference: args.variable.variablesReference});
			vscode.env.openExternal(vscode.Uri.parse('http://localhost:10001'));
		})
	);

	// context.subscriptions.push(
	// 	vscode.commands.registerCommand("luceeDebugger.showLoadedClasses", () => {
	// 		currentDebugSession?.customRequest("showLoadedClasses");
	// 	})
	// );

	vscode.debug.registerDebugAdapterTrackerFactory("cfml", {
		createDebugAdapterTracker(session: vscode.DebugSession) {
			return {
				onWillReceiveMessage(message: any) : void {
					outputChannel.append(JSON.stringify(message, null, 4) + "\n");
				},
				onDidSendMessage(message: any) : void {
					outputChannel.append(JSON.stringify(message, null, 4) + "\n");
				}
			}
		}
	})
}

export function deactivate() {
	currentDebugSession = null;
}