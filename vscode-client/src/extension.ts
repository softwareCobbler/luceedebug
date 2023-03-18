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

	interface DumpResponse {
		content: string
	}

	const webviewPanelByUri : {[uri: string]: vscode.WebviewPanel} = {}
	const updateOrCreateWebview = (uri: vscode.Uri, html: string) => {
		const uriString = uri.toString();
		const panel = webviewPanelByUri[uriString];
		if (panel) {
			panel.webview.html = html;
			panel.reveal(undefined, true);
		}
		else {
			const panel =  vscode.window.createWebviewPanel(
				'luceedebug',
				uri.path,
				vscode.ViewColumn.One,
				{
					enableScripts: true,
				}
			);
			panel.webview.html = html;
			webviewPanelByUri[uriString] = panel;
			panel.onDidDispose(() => {
				delete webviewPanelByUri[uriString];
			});
		}
	}

	context.subscriptions.push(
		vscode.commands.registerCommand("luceedebug.dump", async (args?: Partial<DebugPaneContextMenuArgs>) => {
			if (args?.variable === undefined || args.variable.variablesReference === 0) {
				// This could be called from the command pallette (press F1 and type it)
				// rather than from the debug variables pane context menu. Maybe there is a better
				// way to determine where this was called from, or prevent it from being called anywhere
				// except the debug variables pane context menu.
				//
				// If variablesReference is 0, then the value is a primitive value and we don't service the request
				//
				return;
			}
			
			// need a timeout? or does this cb get wrapped in a timeout by whoever we're passing it to 
			const result : DumpResponse = await currentDebugSession?.customRequest("dump", {variablesReference: args.variable.variablesReference});
			const uri = vscode.Uri.from({scheme: "luceedebug", path: args.variable.name, fragment: args.variable.variablesReference.toString()});
			const html = result.content;
			updateOrCreateWebview(uri, html);
		}),
		vscode.commands.registerCommand("luceedebug.dumpAsJSON", async (args?: Partial<DebugPaneContextMenuArgs>) => {
			if (!currentDebugSession || args?.variable === undefined || args.variable.variablesReference === 0) {
				return;
			}
			
			const result : DumpResponse = await currentDebugSession.customRequest("dumpAsJSON", {variablesReference: args.variable.variablesReference});
			
			let obj : any;
			try {
				obj = JSON.parse(result.content);
			}
			catch {
				obj = "Failed to parse the following JSON:\n" + result.content;
			}

			const uri = vscode.Uri.from({scheme: "luceedebug", path: args.variable.name, fragment: args.variable.variablesReference.toString()});
			const text = JSON.stringify(obj, undefined, 4);

			luceedebugTextDocumentProvider.addOrReplaceTextDoc(uri, text);
			
			const doc = await vscode.workspace.openTextDocument(uri);
			await vscode.window.showTextDocument(doc);
		})
	);

	// context.subscriptions.push(
	// 	vscode.commands.registerCommand("luceeDebugger.showLoadedClasses", () => {
	// 		currentDebugSession?.customRequest("showLoadedClasses");
	// 	})
	// );

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
				breakpoints: [string, string][],
				pathTransforms: string[],
			}
			const data : DebugBreakpointBindingsResponse = await currentDebugSession?.customRequest("debugBreakpointBindings");
			
			const uri = vscode.Uri.from({scheme: "luceedebug", path: "debugBreakpointBindings"});
			const text = "Breakpoints luceedebug has:\n"
				+ data
					.breakpoints
					.sort(([l_idePath],[r_idePath]) => l_idePath < r_idePath ? -1 : 1)
					.map(([idePath, serverPath]) => `  (ide)    ${idePath}\n  (server) ${serverPath}`).join("\n\n")
				+ "\n\nPath transforms:\n"
				+ (data.pathTransforms.length === 0 ? "<<none>>" : data.pathTransforms.map(v => `  ${v}`).join("\n"))
				+ "\n\nFiles luceedebug knows about (all filenames are as the server sees them, and match against breakpoint 'server' paths):\n"
				+ data.canonicalFilenames.sort().map(s => `  ${s}`).join("\n");
			
			luceedebugTextDocumentProvider.addOrReplaceTextDoc(uri, text);
			
			const doc = await vscode.workspace.openTextDocument(uri);
			await vscode.window.showTextDocument(doc);
		})
	)

	context.subscriptions.push(
		vscode.commands.registerCommand("luceedebug.openFileForVariableSourcePath", async (args?: Partial<DebugPaneContextMenuArgs>) => {
			if (!currentDebugSession || !args || args.variable === undefined || args.variable.variablesReference === 0) {
				// doesn't exist or represents a primitive value
				return;
			}

			interface GetSourcePathResponse {
				path: string | null
			}

			const data : GetSourcePathResponse = await currentDebugSession.customRequest("getSourcePath", {variablesReference: args.variable.variablesReference});
			if (!data.path) {
				return;
			}
			const uri = vscode.Uri.from({scheme: "file", path: data.path});
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
