package ro.cobrabm.fexdroid;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** A copy of the game's saves before each start (gow3port/user/savedata_backups/auto-<stamp>),
 *  the last few kept: a session that corrupts a save can be rolled back. Pure Java. */
public final class AutoBackup {
    static final String PREFIX="auto-";

    private AutoBackup() {}

    /** Copies [savedata] to [backups]/auto-[stamp] and keeps the newest [keep] automatic copies.
     *  Returns the copy, or null when there was nothing to save or the copy failed (then removed). */
    public static File run(File savedata,File backups,String stamp,int keep) {
        if (!hasFiles(savedata)) return null;
        File target=new File(backups,PREFIX+stamp);
        for (int n=2;target.exists();n++) target=new File(backups,PREFIX+stamp+"-"+n);
        try {
            copyTree(savedata.toPath(),target.toPath());
        } catch (IOException ex) {
            deleteTree(target);
            return null;
        }
        prune(backups,keep);
        return target;
    }

    static boolean hasFiles(File dir) {
        if (!dir.isDirectory()) return false;
        try (Stream<Path> files=Files.walk(dir.toPath())) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException ex) {
            return false;
        }
    }

    /** Removes the oldest automatic copies beyond [keep] (names sort by their time stamp). */
    static void prune(File backups,int keep) {
        File[] entries=backups.listFiles((dir,name) -> name.startsWith(PREFIX));
        if (entries==null || entries.length<=keep) return;
        List<File> copies=new ArrayList<>();
        Collections.addAll(copies,entries);
        copies.sort((a,b) -> a.getName().compareTo(b.getName()));
        for (int i=0;i<copies.size()-keep;i++) deleteTree(copies.get(i));
    }

    private static void copyTree(Path from,Path to) throws IOException {
        try (Stream<Path> paths=Files.walk(from)) {
            for (Path path : (Iterable<Path>)paths::iterator) {
                Path target=to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path,target,StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static void deleteTree(File root) {
        File[] children=root.listFiles();
        if (children!=null) for (File child : children) deleteTree(child);
        root.delete();
    }
}
