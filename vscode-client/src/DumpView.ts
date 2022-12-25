import * as vscode from "vscode"

export class OneTimeUseDocHolder {
	private docs : {[stringifiedUri: string]: {dispose: () => void, timeoutCancellation: () => void, docText: string}} = {};

	pushDoc(uri: vscode.Uri, docText: string) : void {
		const stringifiedUri = uri.toString();
		this.docs[stringifiedUri] = {
			dispose: () : void => {
				delete this.docs[stringifiedUri];
			},
			timeoutCancellation: (() => {
				const timeoutID = setTimeout(() => this.docs[stringifiedUri]?.dispose(), 5000000);
				return () => clearTimeout(timeoutID);
			})(),
			docText
		}
	}

	getDoc(uri: vscode.Uri) : string | undefined {
		const obj = this.docs[uri.toString()];
		if (obj) {
			const doc = obj.docText;
			obj.dispose();
			return doc;
		}
		else {
			return undefined;
		}
	}
}

interface SimpleInMemoryDocument extends vscode.CustomDocument {
	text: string;
}

export class DumpViewProvider implements vscode.CustomReadonlyEditorProvider {
    static readonly viewType = "luceedebug.dumpView";

	private docHolder = new OneTimeUseDocHolder();
    
    registerDump(uri: vscode.Uri, text: string) {
        this.docHolder.pushDoc(uri, text);
    }

	private constructor(
		private readonly _context: vscode.ExtensionContext
	) {}

    public static register(context: vscode.ExtensionContext) : {dumpViewProvider: DumpViewProvider, disposable: vscode.Disposable} {
        const dumpViewProvider = new DumpViewProvider(context);
		return {
            dumpViewProvider,
            disposable: vscode.window.registerCustomEditorProvider(
                DumpViewProvider.viewType,
                dumpViewProvider,
                {
                    // In the vscode demo extension, they enable `retainContextWhenHidden` which keeps the
                    // webview alive even when it is not visible. We should avoid using this setting
                    // unless is absolutely required as it does have memory overhead.
                    webviewOptions: {
                        retainContextWhenHidden: false,
                    },
                    supportsMultipleEditorsPerDocument: false,
                }
            )
        }
	}
	
	openCustomDocument(uri: vscode.Uri, openContext: vscode.CustomDocumentOpenContext, token: vscode.CancellationToken): SimpleInMemoryDocument | Thenable<SimpleInMemoryDocument> {
		return {
			uri,
			text: this.docHolder.getDoc(uri) ?? "",
			dispose() {}
		}
	}

	resolveCustomEditor(document: SimpleInMemoryDocument, webviewPanel: vscode.WebviewPanel, token: vscode.CancellationToken): void | Thenable<void> {
		webviewPanel.webview.options = {
			enableScripts: true,
		};
		webviewPanel.webview.html = document.text;
	}
}