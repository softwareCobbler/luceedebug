<cfscript>
    function foo() {
        try {
            bar();
        }
        catch (any e) {
            0+0;
        }
    }

    function bar() {
        baz();
    }

    function baz() {
        try {
            qux();
        }
        finally {
            0+0;
        }
    }

    function qux() {
        last();
    }

    function last() {
        throw "e";
    }

    foo();
</cfscript>