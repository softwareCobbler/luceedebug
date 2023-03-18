<cfscript>
    function foo(n) {
        return {foo:foo}
    }

    foo(1).foo(2).foo(3).foo(4);
    foo(6);
    foo(7);
</cfscript>