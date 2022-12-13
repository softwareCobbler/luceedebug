package luceedebug;

interface ICfPathTransform {
    public String cfToIde(String s);
    public String ideToCf(String s);

    /**
     * like toString, but contractually for trace purposes
     */
    public String asTraceString();
}
