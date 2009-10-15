/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.alchim31.maven.basicwebstart;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

import com.google.common.base.Joiner;

/**
 * Note 2009-09-21 : no longer use ArchiveManager, because it failed when running in 4 thread in //, when it execute external command 'sh -c ls -1lnaR ...'
 * @author dwayne
 */
public class JarUtil {
    private final static List<String> EXT_ARRAY = Arrays.asList(new String[]{ "DSA", "RSA", "SF" });
    private final static FileFilter _removeSignatureFileFilter = new FileFilter() {
        public boolean accept(File file) {
            String extension = FileUtils.getExtension(file.getAbsolutePath());
            return (EXT_ARRAY.contains(extension));
        }
    };

    private static File _tmpRootDir = null;

    // TODO check that tempDir is removed on shutdown
    public static File createTempDir() throws Exception {
        if (_tmpRootDir == null) {
            _tmpRootDir = new File(System.getProperty("java.io.tmpdir", "/tmp"), "jarutil.tmp");
//            Runtime.getRuntime().addShutdownHook(new Thread(){
//                @Override
//                public void run() {
//                    super.run();
//                    try {
//                        if (_tmpRootDir.exists()) {
//                            FileUtils.deleteDirectory(_tmpRootDir);
//                        }
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            });
        }
        File dir = new File(_tmpRootDir, String.valueOf(System.currentTimeMillis()));
        dir.mkdirs();
        return dir;
    }

    public static File unjar(File jarFile) throws Exception {
        File tempDirParent = createTempDir();

        // create temp dir
        File tempDir = new File( tempDirParent, jarFile.getName() + ".dir" );

        if (!tempDir.mkdir()) {
            throw new IOException( "Error creating temporary directory: " + tempDir );
        }
        // FIXME we probably want to be more security conservative here.
        // it's very easy to guess where the directory will be and possible
        // to access/change its contents before the file is rejared..

        // extract jar into temporary directory
        return unjar(jarFile, tempDir);
    }
    
    public static File unjar(File jarFile, File outdir) throws Exception {
        JarFile jfile = new JarFile(jarFile);
        Enumeration<JarEntry> e = jfile.entries();
        while(e.hasMoreElements()) {
            JarEntry entry = e.nextElement();
            File f = new File(outdir, entry.getName());
            if (entry.isDirectory()) {
                f.mkdirs();
            } else {
                File parent = f.getParentFile();
                if (!parent.exists() && !parent.mkdirs() || !parent.isDirectory()) {
                    throw new IOException("can't create directory :" + parent);
                }
                InputStream in = jfile.getInputStream(entry);
                OutputStream os = new FileOutputStream(f);
                try {
                    IOUtil.copy(in, os);
                    os.flush();
                } finally {
                    IOUtil.close(in);
                    IOUtil.close(os);
                }
            }
            long lastModified = entry.getTime();
            if (lastModified != -1) {
                f.setLastModified(lastModified);
            }
        }
        return outdir;
    }
    
    //TODO optimisation : avoid exploding files on FS (memory pipeline)
    public static void rejar(File jarIn, File jarOut, boolean compress, boolean unsign, Log log) throws Exception {
        File explodedJarDir = unjar(jarIn);
        if (unsign) {
            unsign(explodedJarDir);
        }
        jar(explodedJarDir, jarOut, compress, log);
        FileUtils.deleteDirectory(explodedJarDir);
    }
    
    public static void unsign(File explodedJarDir) throws Exception {
        // create and check META-INF directory
        File metaInf = new File( explodedJarDir, "META-INF" );
        if ( !metaInf.isDirectory() ) {
//            verboseLog( "META-INT dir not found : nothing to do for file: " + jarFile.getAbsolutePath() );
            return;
        }

        // filter signature files and remove them
        File[] filesToRemove = metaInf.listFiles(_removeSignatureFileFilter);
        if ( filesToRemove.length == 0 ) {
//            verboseLog( "no files match " + toString(EXT_ARRAY) + " : nothing to do for file: " + jarFile.getAbsolutePath() );
            return;
        }
        for ( int i = 0; i < filesToRemove.length; i++ ) {
            if ( !filesToRemove[i].delete() ) {
                throw new IOException( "Error removing signature file: " + filesToRemove[i] );
            }
//            verboseLog("remove file :" + filesToRemove[i]);
        }
    }

    public static void jar(File explodedJarDir, File jarFile, boolean compress, Log log) throws Exception {
        try {
//        JarArchiver jarArchiver = (JarArchiver) archiverManager.getArchiver( "jar" );
//        jarArchiver.setCompress( compress );
//        jarArchiver.setUpdateMode( false );
//        jarArchiver.addDirectory( explodedJarDir );
//        jarArchiver.setDestFile( jarFile );
//        File manifestFile = new File(explodedJarDir, "META-INF/MANIFEST.MF");
//        if (manifestFile.exists()) {
//            jarArchiver.setManifest(manifestFile);
//        }
        //jnlp (1.6.0_u14) doesn't like empty jar (with nothing except META-INF)
        File indexList = new File( explodedJarDir, "META-INF/INDEX.LIST" );
        if (indexList.exists()) {
            indexList.delete();
        }
        File manifestFile = new File( explodedJarDir, JarFile.MANIFEST_NAME );
        if (!manifestFile.exists()) {
            FileOutputStream os = new FileOutputStream(manifestFile);
            try {
                new Manifest().write(os);
            } finally {
                IOUtil.close(os);
            }
        }
        File[] children = explodedJarDir.listFiles();
        if ( children.length == 0 || (children.length == 1 && "META-INF".equals(children[0].getName().toUpperCase())) ) {
            FileUtils.fileWrite(new File(explodedJarDir, "__no_empty.txt").getAbsolutePath(), "fake : no empty file for jnlp");
        }
        //jar cvfm classes.jar mymanifest -C foo/ .
        CommandLine commandLine = new CommandLine( findJavaExec("jar") );
        commandLine.addArguments(new String[]{"cf", jarFile.getCanonicalPath(), "-C", explodedJarDir.getCanonicalPath(), "."});
        exec(commandLine, log);

//        sun.tools.jar.Main jartool = new sun.tools.jar.Main(System.out, System.err, "jar");
//        if (!jartool.run(new String[]{"cMf", jarFile.getCanonicalPath(), "-C", explodedJarDir.getCanonicalPath(), "."})) {
//            throw new Exception("failed to execute sun.tools.jar.Main.run(...)"); 
//        }

        
//        jarArchiver.setIndex(true);
//        jarArchiver.createArchive();
        
//        CheckedOutputStream checksum = new CheckedOutputStream(new FileOutputStream(jarFile), new Adler32());
//        ZipOutputStream jos = new ZipOutputStream(checksum);
//        try {
//            if (!compress) {
//                jos.setLevel(ZipOutputStream.STORED);
//            }
//            addToJar(jos, explodedJarDir, explodedJarDir.getCanonicalPath());
//        } finally {
//            jos.finish();
//            IOUtil.close(jos);
//        }
//        System.out.println("checksum: "+checksum.getChecksum().getValue());

//        //simple check
//        System.out.println("check --> " + jarFile);
//        JarFile jf = new JarFile(jarFile);
//        System.out.println("check --> " + jarFile + " : " +jf);
//        jf.close();
        } catch (Exception exc) {
            throw new RuntimeException("failed to jar("+ explodedJarDir +", " + jarFile +", " +compress +")", exc);
        }
    }

    private static void addToJar(ZipOutputStream jos, File dir, String basedir) throws Exception {
        for(File f : dir.listFiles()) {
            if (f.isDirectory()) {
                String entryName = f.getCanonicalPath().substring(basedir.length()+1).replace("\\", "/");
                entryName = entryName.endsWith(File.separator) ? entryName : entryName + "/";
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(f.lastModified());
                entry.setSize(0);
                //entry.setMethod(ZipEntry.STORED);
                jos.putNextEntry(entry);
                jos.closeEntry();
                addToJar(jos, f, basedir);
            } else {
                String entryName = f.getCanonicalPath().substring(basedir.length()+1).replace("\\", "/");
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(f.lastModified());
                entry.setSize(f.length());
                //entry.setMethod(ZipEntry.STORED);
                jos.putNextEntry(entry);
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f), 2048);
                try {
                    IOUtil.copy(bis, jos);
                    //jos.flush();
                } finally {
                    IOUtil.close(bis);
                    jos.closeEntry();
                }
            }
        }
    }
    
    public static void createIndex(File jar, final Log log) throws Exception {
        CommandLine commandLine = new CommandLine( findJavaExec("jar") );
        commandLine.addArguments(new String[]{"iv", jar.getCanonicalPath()});
//        commandLine.addArguments(Iterables.toArray(Iterables.transform(jars, new Function<File, String>(){
//            public String apply(File arg0) {
//                try {
//                    return arg0.getCanonicalPath();
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        }), String.class));
        exec(commandLine, false, log);
    }
    
    public static File pack(File jar, String[] options, final Log log) throws Exception {
//        Packer packer = Pack200.newPacker();

//    // Initialize the state by setting the desired properties
//    Map p = packer.properties();
//    // take more time choosing codings for better compression
//    p.put(Packer.EFFORT, "7");  // default is "5"
//    // use largest-possible archive segments (>10% better compression).
//    p.put(Packer.SEGMENT_LIMIT, "-1");
//    // reorder files for better compression.
//    p.put(Packer.KEEP_FILE_ORDER, Packer.FALSE);
//    // smear modification times to a single value.
//    p.put(Packer.MODIFICATION_TIME, Packer.LATEST);
//    // ignore all JAR deflation requests,
//    // transmitting a single request to use "store" mode.
//    p.put(Packer.DEFLATE_HINT, Packer.FALSE);
//    // discard debug attributes
//    p.put(Packer.CODE_ATTRIBUTE_PFX+"LineNumberTable", Packer.STRIP);
//    // throw an error if an attribute is unrecognized
//    p.put(Packer.UNKNOWN_ATTRIBUTE, Packer.ERROR);
//    // pass one class file uncompressed:
//    p.put(Packer.PASS_FILE_PFX+0, "mutants/Rogue.class");
        File back = new File(jar.getAbsolutePath() + ".pack.gz");
//        JarFile in = null;
//        OutputStream out = null;
//        try {
//            in = new JarFile(jar);
//            out = new GZIPOutputStream(new FileOutputStream(back));
//            packer.pack(in, out);
//            return back;
//        } finally {
//            //IOUtil.close(in);
//            IOUtil.close(out);
//        }
        CommandLine commandLine = new CommandLine(findJavaExec("pack200"));
        if (options != null && options.length > 0) {
            commandLine.addArguments(options);
        }
        // pack200 doesn't work for jar >1MB by default
        if (jar.length() > 1024*1024) {
            System.out.println(">> big jar (" + jar.length() / 1024 + ") => modify segment-limit of pack");
            commandLine.addArguments(new String[]{"--segment-limit=-1"});
        }
        commandLine.addArguments(new String[]{back.getAbsolutePath(), jar.getAbsolutePath()});
        exec(commandLine, log);
        return back;
    }

    public static void repack(File jar, String[] options, final Log log) throws Exception {
        CommandLine commandLine = new CommandLine(findJavaExec("pack200"));
        if (options != null && options.length > 0) {
            commandLine.addArguments(options);
        }
        // pack200 doesn't work for jar >1MB by default
        if (jar.length() > 1024*1024) {
            System.out.println(">> big jar (" + jar.length() / 1024 + ") => modify segment-limit of pack");
            commandLine.addArguments(new String[]{"--segment-limit=-1"});
        }
        commandLine.addArguments(new String[]{"--repack", jar.getAbsolutePath()});
        exec(commandLine, log);
    }

    public static void unpack(File pack, File jar, Log log) throws Exception {
        CommandLine commandLine = new CommandLine(findJavaExec("unpack200"));
        commandLine.addArguments(new String[]{pack.getAbsolutePath(), jar.getAbsolutePath()});
        exec(commandLine, log);
    }

    //TODO throws an exception or return a boolean is the verification failed
    public static void verifySignature(File jar, Log log) throws Exception {
        CommandLine commandLine = new CommandLine( findJavaExec("jarsigner") );
        commandLine.addArguments(new String[]{"-verify", jar.getAbsolutePath()});
        exec(commandLine, log);
    }
    
    private static File findJavaExec(String name) throws Exception {
        File jhome = new File(System.getProperty("java.home"));
        File f = findJavaExec(jhome, name);
        if (!f.exists() && jhome.getName().contains("jre")) {
            f = findJavaExec(jhome.getParentFile(), name);
        }
        return f;
    }
    
    private static File findJavaExec(File jhome, String name) throws Exception {
        File f = new File(jhome, "bin/" + name);
        if (!f.exists()) {
            f = new File(jhome, "bin/" + name + ".exe");
        }
        return f;
    }

    //TODO use commons-exec instead of plexus
    private static void exec(CommandLine commandLine, Log log) throws Exception {
        exec(commandLine, true, log);
    }
    
    private static void exec(CommandLine commandLine, boolean throwFailure, Log log) throws Exception {
        try {
//        StreamConsumer stdout = new StreamConsumer() {
//            public void consumeLine( String line ) {
//                log.info( line );
//            }
//        };
//        StreamConsumer sterr = new StreamConsumer() {
//            public void consumeLine( String line ) {
//                log.info( line );
//            }
//        };
            if ("true".equalsIgnoreCase(System.getProperty("displayCmd"))) {
                log.info("cmd : " + commandLine.toString());
            } else if (log.isDebugEnabled()) {
                log.debug("cmd :"+ commandLine.toString());
            }
            File exec = new File(commandLine.getExecutable());
            if (!exec.exists()) {
                String msg = "exec not found : " + exec;
                log.error(msg);
                throw new IllegalStateException(msg);
            }
            Executor executor = new DefaultExecutor();
            executor.execute(commandLine);
        } catch (Exception exc) {
            if (throwFailure) {
                throw exc;
            } else {
                log.warn(exc);
            }
        }
    }
}
