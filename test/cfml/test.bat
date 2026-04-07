cls
SET JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot
set testLabels=dap
set testFilter=
set LUCEE_LOGGING_FORCE_APPENDER=console
set LUCEE_LOGGING_FORCE_LEVEL=info

ant -buildfile="D:/work/script-runner/build.xml" -Dwebroot="D:/work/lucee7/test" -Dexecute="bootstrap-tests.cfm" -DluceeVersionQuery="7.0/all/light" -DtestAdditional="D:\work\lucee-extensions\luceedebug\test\cfml" -DtestLabels="%testLabels%" -DtestFilter="%testFilter%" -DtestDebug="false"
