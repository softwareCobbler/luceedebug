package luceedebug;

/**
 * Simple thread information for DAP.
 * Replaces JDWP ThreadReference dependency in ILuceeVm.
 */
public class ThreadInfo {
	public final long id;
	public final String name;

	public ThreadInfo(long id, String name) {
		this.id = id;
		this.name = name;
	}
}
