# DAP Tests for luceedebug

TestBox-based tests for the Debug Adapter Protocol functionality.

## Architecture

These tests require **two separate Lucee instances**:

1. **Debuggee** - Lucee with luceedebug enabled
   - DAP server on port 10000
   - HTTP server on port 8888
   - Serves the test artifacts

2. **Test Runner** - Lucee running TestBox tests
   - Connects to debuggee via DAP
   - Triggers HTTP requests to artifacts
   - **Must NOT have luceedebug enabled** (or it would freeze when breakpoints hit)

## Running Tests Locally

### Step 1: Start the Debuggee

Use your existing Lucee dev server with luceedebug, or use Lucee Express:

1. Download express template from [Lucee Express Templates](https://update.lucee.org/rest/update/provider/expressTemplates)
2. Drop your Lucee JAR in `lib/`
3. Set env vars: `LUCEE_DAP_SECRET=testing` and `LUCEE_DAP_PORT=10000`
4. Start Tomcat on port 8888

### Step 2: Run the Tests

```bash
cd test/cfml
test.bat
```

Or filter to specific tests:
```bash
set testFilter=BreakpointsTest
test.bat
```

## Test Files

- `DapClient.cfc` - DAP protocol client
- `DapTestCase.cfc` - Base test class with helpers
- `*Test.cfc` - Individual test suites
- `artifacts/*.cfm` - Target files that get debugged

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DAP_HOST` | localhost | Debuggee DAP host |
| `DAP_PORT` | 10000 | Debuggee DAP port |
| `DEBUGGEE_HTTP` | http://localhost:8888 | Debuggee HTTP URL |
| `DAP_DEBUG` | false | Enable DAP client debug logging |

## Feature Detection

Tests use capability-based skipping. If a capability isn't supported:
- Native-only features skip on agent mode
- Version-specific features skip on older Lucee

Example:
```cfml
function testSetVariable() skip="!supportsSetVariable()" {
    // Only runs if setVariable is supported
}
```

## Adding Tests

1. Create `YourFeatureTest.cfc` extending `DapTestCase`
2. Add label `labels="dap"`
3. Add target artifact in `artifacts/` if needed
4. Use `skip` attribute for capability-based skipping
