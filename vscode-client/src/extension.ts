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

	const luceedebugTextDocumentProvider = new (class implements vscode.TextDocumentContentProvider {
		private docs : {[uri: string]: string} = {};
		
		private onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
		onDidChange = this.onDidChangeEmitter.event;

		addOrReplaceTextDoc(uri: vscode.Uri, text: string) {
			this.docs[uri.toString()] = text;
			this.onDidChangeEmitter.fire(uri);
		}
		
		provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): vscode.ProviderResult<string> {
			return this.docs[uri.toString()] ?? null;
		}
	})();

	vscode.workspace.registerTextDocumentContentProvider("luceedebug", luceedebugTextDocumentProvider);
	context.subscriptions.push(
		vscode.commands.registerCommand("luceedebug.debugBreakpointBindings", async () => {
			interface DebugBreakpointBindingsResponse {
				canonicalFilenames: string[],
				breakpoints: [string, string][]
			}
			const data : DebugBreakpointBindingsResponse = await currentDebugSession?.customRequest("debugBreakpointBindings");
			
			const uri = vscode.Uri.from({scheme: "luceedebug", path: "debugBreakpointBindings"});
			const text = "Breakpoints luceedebug has:\n"
				+ data
					.breakpoints
					.sort(([l_idePath],[r_idePath]) => l_idePath < r_idePath ? -1 : 1)
					.map(([idePath, serverPath]) => `  (ide)    ${idePath}\n  (server) ${serverPath}`).join("\n\n")
				+ "\n\nFiles luceedebug knows about (all filenames are as the server sees them, and match against breakpoint 'server' paths):\n"
				+ data.canonicalFilenames.sort().map(s => `  ${s}`).join("\n");
			
			luceedebugTextDocumentProvider.addOrReplaceTextDoc(uri, text);
			
			const doc = await vscode.workspace.openTextDocument(uri);
			await vscode.window.showTextDocument(doc);
		})
	)

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