package luceedebug.test;

public class AdviceAdapterReceiver {
    public Object call(final /*PageContext*/ Object pc) throws Throwable {
        // on enter:
        // lucee.runtime.CFMLFactoryImpl.luceedebugTracker.pushCfFrame(pc);
        // before exit:
        //n lucee.runtime.CFMLFactoryImpl.luceedebugTracker.popCfFrame(pc);
        return new Object();
    }

    public Object udfCall(final /*PageContext*/ Object pageContext, final /*UDF*/ Object udf, final int functionIndex) throws Throwable {
        return new Object();
    }
}
