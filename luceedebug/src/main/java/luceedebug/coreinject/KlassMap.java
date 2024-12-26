package luceedebug.coreinject;

import java.util.HashMap;

import luceedebug.Config;
import luceedebug.StrongString.CanonicalServerAbsPath;

import com.sun.jdi.*;

class KlassMap {

    final public CanonicalServerAbsPath sourceName; 
    final public HashMap<Integer, Location> lineMap;
    private final ClassObjectReference objRef;
    
    final public ReferenceType refType;

    private KlassMap(Config config, ReferenceType refType) throws AbsentInformationException {
        objRef = refType.classObject();

        String sourceName = refType.sourceName();
        var lineMap = new HashMap<Integer, Location>();
        
        for (var loc : refType.allLineLocations()) {
            lineMap.put(loc.lineNumber(), loc);
        }

        this.sourceName = new CanonicalServerAbsPath(Config.canonicalizeFileName(sourceName));

        this.lineMap = lineMap;
        this.refType = refType;
    }

    boolean isCollected() {
        return objRef.isCollected();
    }

    /**
     * May return null if ReferenceType throws an AbsentInformationException, which the caller
     * should interpret as "we can't do anything meaningful with this file"
     */
    static KlassMap maybeNull_tryBuildKlassMap(Config config, ReferenceType refType) {
        try {
            return new KlassMap(config, refType);
        }
        catch (AbsentInformationException e) {
            return null;
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }

        // unreachable
        return null;
    }

    @Override
    public boolean equals(Object e) {
        if (e instanceof KlassMap) {
            return ((KlassMap)e).sourceName.equals(this.sourceName);
        }
        return false;
    }
}
