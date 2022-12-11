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

	// context.subscriptions.push(
	// 	vscode.commands.registerCommand("luceeDebugger.showLoadedClasses", () => {
	// 		currentDebugSession?.customRequest("showLoadedClasses");
	// 	}));

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