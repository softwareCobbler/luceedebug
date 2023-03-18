<cfscript>
    function foo(n) {
        return {foo:foo}
    }

    foo(1).foo(2).foo(3).foo(4);
    foo(5);
    foo(6);
</cfscript>