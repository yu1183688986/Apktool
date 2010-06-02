/*
 *  Copyright 2010 Ryszard Wiśniewski <brut.alll@gmail.com>.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */

package brut.androlib.res;

import brut.androlib.AndrolibException;
import brut.androlib.err.CantFindFrameworkResException;
import brut.androlib.res.data.*;
import brut.androlib.res.data.value.ResXmlSerializable;
import brut.androlib.res.decoder.*;
import brut.androlib.res.decoder.ARSCDecoder.FlagsOffset;
import brut.androlib.res.util.ExtFile;
import brut.androlib.res.util.ExtMXSerializer;
import brut.common.BrutException;
import brut.directory.*;
import brut.util.*;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.xmlpull.v1.XmlSerializer;

/**
 * @author Ryszard Wiśniewski <brut.alll@gmail.com>
 */
final public class AndrolibResources {
    public ResTable getResTable(ExtFile apkFile) throws AndrolibException {
        ResTable resTable = new ResTable(this);
        loadMainPkg(resTable, apkFile);
        return resTable;
    }

    public ResPackage loadMainPkg(ResTable resTable, ExtFile apkFile)
            throws AndrolibException {
        LOGGER.info("Loading resource table...");
        ResPackage[] pkgs = getResPackagesFromApk(apkFile, resTable);
        ResPackage pkg = null;

        switch (pkgs.length) {
            case 1:
                pkg = pkgs[0];
                break;
            case 2:
                if (pkgs[0].getName().equals("android")) {
                    LOGGER.warning("Skipping \"android\" package group");
                    pkg = pkgs[1];
                }
                break;
        }

        if (pkg == null) {
            throw new AndrolibException(
                "Arsc files with zero or multiple packages");
        }

        resTable.addPackage(pkg, true);
        return pkg;
    }

    public ResPackage loadFrameworkPkg(ResTable resTable, int id,
            String frameTag) throws AndrolibException {
        File apk = getFrameworkApk(id, frameTag);

        LOGGER.info("Loading resource table from file: " + apk);
        ResPackage[] pkgs = getResPackagesFromApk(new ExtFile(apk), resTable);

        if (pkgs.length != 1) {
            throw new AndrolibException(
                "Arsc files with zero or multiple packages");
        }

        ResPackage pkg = pkgs[0];
        if (pkg.getId() != id) {
            throw new AndrolibException("Expected pkg of id: " +
                String.valueOf(id) + ", got: " + pkg.getId());
        }

        resTable.addPackage(pkg, false);
        return pkg;
    }

    public void decode(ResTable resTable, ExtFile apkFile, File outDir)
            throws AndrolibException {
        Duo<ResFileDecoder, ResAttrDecoder> duo = getResFileDecoder();
        ResFileDecoder fileDecoder = duo.m1;
        ResAttrDecoder attrDecoder = duo.m2;

        attrDecoder.setCurrentPackage(
            resTable.listMainPackages().iterator().next());

        Directory in, out, out9Patch;
        try {
            in = apkFile.getDirectory();
            out = new FileDirectory(outDir);

            fileDecoder.decode(
                in, "AndroidManifest.xml", out, "AndroidManifest.xml", "xml");

            out9Patch = out.createDir("9patch/res");
            in = in.getDir("res");
            out = out.createDir("res");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }

        ExtMXSerializer xmlSerializer = getResXmlSerializer();
        for (ResPackage pkg : resTable.listMainPackages()) {
            attrDecoder.setCurrentPackage(pkg);
            for (ResResource res : pkg.listFiles()) {
                fileDecoder.decode(res, in, out, out9Patch);
            }
            for (ResValuesFile valuesFile : pkg.listValuesFiles()) {
                generateValuesFile(valuesFile, out, xmlSerializer);
            }
            generatePublicXml(pkg, out, xmlSerializer);
        }
    }

    public void aaptPackage(File apkFile, File manifest, File resDir,
            File rawDir, File assetDir, File[] include,
            boolean update, boolean framework) throws AndrolibException {
        List<String> cmd = new ArrayList<String>();

        cmd.add("aapt");
        cmd.add("p");
        if (update) {
            cmd.add("-u");
        }
        cmd.add("-F");
        cmd.add(apkFile.getAbsolutePath());

        if (framework) {
            cmd.add("-x");
            cmd.add("-0");
            cmd.add("arsc");
        }

        if (include != null) {
            for (File file : include) {
                cmd.add("-I");
                cmd.add(file.getPath());
            }
        }
        if (resDir != null) {
            cmd.add("-S");
            cmd.add(resDir.getAbsolutePath());
        }
        if (manifest != null) {
            cmd.add("-M");
            cmd.add(manifest.getAbsolutePath());
        }
        if (assetDir != null) {
            cmd.add("-A");
            cmd.add(assetDir.getAbsolutePath());
        }
        if (rawDir != null) {
            cmd.add(rawDir.getAbsolutePath());
        }

        try {
            OS.exec(cmd.toArray(new String[0]));
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean detectWhetherAppIsFramework(File appDir)
            throws AndrolibException {
        File publicXml = new File(appDir, "res/values/public.xml");
        if (! publicXml.exists()) {
            return false;
        }

        Iterator<String> it;
        try {
            it = IOUtils.lineIterator(
                new FileReader(new File(appDir, "res/values/public.xml")));
        } catch (FileNotFoundException ex) {
            throw new AndrolibException(
                "Could not detect whether app is framework one", ex);
        }
        it.next();
        it.next();
        return it.next().contains("0x01");
    }

    public void tagSmaliResIDs(ResTable resTable, File smaliDir)
            throws AndrolibException {
        new ResSmaliUpdater().tagResIDs(resTable, smaliDir);
    }

    public void updateSmaliResIDs(ResTable resTable, File smaliDir) throws AndrolibException {
        new ResSmaliUpdater().updateResIDs(resTable, smaliDir);
    }

    public Duo<ResFileDecoder, ResAttrDecoder> getResFileDecoder() {
        ResStreamDecoderContainer decoders =
            new ResStreamDecoderContainer();
        decoders.setDecoder("raw", new ResRawStreamDecoder());

        ResAttrDecoder attrDecoder = new ResAttrDecoder();
        AXmlResourceParser axmlParser = new AXmlResourceParser();
        axmlParser.setAttrDecoder(attrDecoder);
        decoders.setDecoder("xml",
            new XmlPullStreamDecoder(axmlParser, getResXmlSerializer()));

        return new Duo<ResFileDecoder, ResAttrDecoder>(
            new ResFileDecoder(decoders), attrDecoder);
    }

    public ExtMXSerializer getResXmlSerializer() {
        ExtMXSerializer serial = new ExtMXSerializer();
        serial.setProperty(serial.PROPERTY_SERIALIZER_INDENTATION, "    ");
        serial.setProperty(serial.PROPERTY_SERIALIZER_LINE_SEPARATOR,
            System.getProperty("line.separator"));
        serial.setProperty(ExtMXSerializer.PROPERTY_DEFAULT_ENCODING, "UTF-8");
        return serial;
    }

    private void generateValuesFile(ResValuesFile valuesFile, Directory out,
            XmlSerializer serial) throws AndrolibException {
        try {
            OutputStream outStream = out.getFileOutput(valuesFile.getPath());
            serial.setOutput((outStream), null);
            serial.startDocument(null, null);
            serial.startTag(null, "resources");

            for (ResResource res : valuesFile.listResources()) {
                if (valuesFile.isSynthesized(res)) {
                    continue;
                }
                ((ResXmlSerializable) res.getValue())
                    .serializeToXml(serial, res);
            }
            
            serial.endTag(null, "resources");
            serial.endDocument();
            serial.flush();
            outStream.close();
        } catch (IOException ex) {
            throw new AndrolibException(
                "Could not generate: " + valuesFile.getPath(), ex);
        } catch (DirectoryException ex) {
            throw new AndrolibException(
                "Could not generate: " + valuesFile.getPath(), ex);
        }
    }

    private void generatePublicXml(ResPackage pkg, Directory out,
            XmlSerializer serial) throws AndrolibException {
        try {
            OutputStream outStream = out.getFileOutput("values/public.xml");
            serial.setOutput(outStream, null);
            serial.startDocument(null, null);
            serial.startTag(null, "resources");

            for (ResResSpec spec : pkg.listResSpecs()) {
                serial.startTag(null, "public");
                serial.attribute(null, "type", spec.getType().getName());
                serial.attribute(null, "name", spec.getName());
                serial.attribute(null, "id", String.format(
                    "0x%08x", spec.getId().id));
                serial.endTag(null, "public");
            }

            serial.endTag(null, "resources");
            serial.endDocument();
            serial.flush();
            outStream.close();
        } catch (IOException ex) {
            throw new AndrolibException(
                "Could not generate public.xml file", ex);
        } catch (DirectoryException ex) {
            throw new AndrolibException(
                "Could not generate public.xml file", ex);
        }
    }

    private ResPackage[] getResPackagesFromApk(ExtFile apkFile,
            ResTable resTable) throws AndrolibException {
        try {
            return ARSCDecoder.decode(
                apkFile.getDirectory().getFileInput("resources.arsc"), false,
                resTable).getPackages();
        } catch (DirectoryException ex) {
            throw new AndrolibException(
                "Could not load resources.arsc from file: " + apkFile, ex);
        }
    }

    public File getFrameworkApk(int id, String frameTag)
            throws AndrolibException {
        File dir = getFrameworkDir();
        File apk;

        if (frameTag != null) {
            apk = new File(dir, String.valueOf(id) + '-' + frameTag + ".apk");
            if (apk.exists()) {
                return apk;
            }
        }

        apk = new File(dir, String.valueOf(id) + ".apk");
        if (apk.exists()) {
            return apk;
        }

        if (id == 1) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = AndrolibResources.class.getResourceAsStream(
                    "/brut/androlib/android-framework.jar");
                out = new FileOutputStream(apk);
                IOUtils.copy(in, out);
                return apk;
            } catch (IOException ex) {
                throw new AndrolibException(ex);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ex) {}
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ex) {}
                }
            }
        }

        throw new CantFindFrameworkResException(id);
    }

    public void publicizeResources(File arscFile) throws AndrolibException {
        byte[] data = new byte[(int) arscFile.length()];

        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(arscFile);
            in.read(data);

            publicizeResources(data);

            out = new FileOutputStream(arscFile);
            out.write(data);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {}
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {}
            }
        }
    }

    public void publicizeResources(byte[] arsc) throws AndrolibException {
        for (FlagsOffset flags :
                ARSCDecoder.decode(new ByteArrayInputStream(arsc), true)
                .getFlagsOffsets()) {
            int offset = flags.offset + 3;
            int end = offset + 4 * flags.count;
            while(offset < end) {
                arsc[offset] |= (byte) 0x40;
                offset += 4;
            }
        }
    }

    private File getFrameworkDir() throws AndrolibException {
        File dir = new File(System.getProperty("user.home") +
            File.separatorChar + "apktool" + File.separatorChar + "framework");
        if (! dir.exists()) {
            if (! dir.mkdirs()) {
                throw new AndrolibException("Can't create directory: " + dir);
            }
        }
        return dir;
    }

    public File getAndroidResourcesFile() throws AndrolibException {
        try {
            return Jar.getResourceAsFile("/brut/androlib/android-framework.jar");
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    public static String escapeForResXml(String value) {
        if (value.isEmpty()) {
            return value;
        }

        StringBuilder out = new StringBuilder(value.length() + 10);
        char[] chars = value.toCharArray();

        switch (chars[0]) {
            case '@':
            case '#':
            case '?':
                out.append('\\');
        }

        boolean space = true;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (c == ' ') {
                if (space) {
                    out.append("\\u0020");
                } else {
                    out.append(c);
                    space = true;
                }
                continue;
            }

            space = false;
            switch (c) {
                case '\\':
                case '\'':
                case '"':
                    out.append('\\');
                    break;
                case '\n':
                    out.append("\\n");
                    continue;
            }
            out.append(c);
        }

        if (space && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
            out.append("\\u0020");
        }

        return out.toString();
    }

    private final static Logger LOGGER =
        Logger.getLogger(AndrolibResources.class.getName());
}
