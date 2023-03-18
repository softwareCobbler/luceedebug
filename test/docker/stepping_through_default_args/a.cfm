<cfscript>
    function foo(
        a = 0,     b = 0,
        c = {a:1}, d = 0,
        e = {b:1}, f = 0
    ) {
        return 0;
    }

    foo(d = 42);
</cfscript>