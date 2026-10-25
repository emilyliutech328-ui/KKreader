
package me.ag2s.umdlib.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.Inflater;
import java.nio.charset.StandardCharsets;


public class UmdUtils {

    private static final int EOF = -1;
    private static final int BUFFER_SIZE = 8 * 1024;
    // [自建版修复 P1] 解压输出上限，防止压缩包炸弹/损坏文件把内存吃满
    private static final int MAX_DECOMPRESS_SIZE = 100 * 1024 * 1024;


    /**
     * 将字符串编码成Unicode形式的byte[]
     *
     * @param s 要编码的字符串
     * @return 编码好的byte[]
     */
    public static byte[] stringToUnicodeBytes(String s) {
        if (s == null) {
            throw new NullPointerException();
        }

        return s.getBytes(StandardCharsets.UTF_16LE);
    }

    /**
     * 将编码成Unicode形式的byte[]解码成原始字符串
     *
     * @param bytes 编码成Unicode形式的byte[]
     * @return 原始字符串
     */
    public static String unicodeBytesToString(byte[] bytes) {
        //修复一些文件属性空值的问题
        if (bytes==null){
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    /**
     * 将byte[]转化成Hex形式
     *
     * @param bArr byte[]
     * @return 目标HEX字符串
     */
    public static String toHex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length);
        String sTmp;

        for (int i = 0; i < bArr.length; i++) {
            sTmp = Integer.toHexString(0xFF & bArr[i]);
            if (sTmp.length() < 2)
                sb.append(0);
            sb.append(sTmp.toUpperCase());
        }

        return sb.toString();
    }

    /**
     * 解压缩zip的byte[]
     *
     * @param compress zippered byte[]
     * @return decompressed byte[]
     * @throws Exception 解码时失败时
     */
    public static byte[] decompress(byte[] compress) throws Exception {
        Inflater inflater = new Inflater();
        inflater.reset();
        inflater.setInput(compress);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(compress.length);
        try (baos) {
            byte[] buff = new byte[BUFFER_SIZE];
            int zeroCount = 0;
            // [自建版修复 P1] 原实现在输入耗尽但流未正常结束时（inflate 返回 0
            // 且 finished() 一直为 false）会空转死循环，损坏/恶意的 UMD 会直接卡死线程。
            while (!inflater.finished()) {
                int count = inflater.inflate(buff);
                if (count > 0) {
                    baos.write(buff, 0, count);
                    zeroCount = 0;
                    if (baos.size() > MAX_DECOMPRESS_SIZE) {
                        throw new IOException("UMD 解压数据过大, 已中止");
                    }
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    // 输入已耗尽或需要字典，解压流无法继续：截断/损坏数据，直接结束
                    break;
                } else if (++zeroCount > 8) {
                    // 兜底：连续多次零产出仍然未结束，强制退出避免死循环
                    break;
                }
            }
        } finally {
            inflater.end();
        }
        return baos.toByteArray();
    }


    public static void saveFile(File f, byte[] content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            bos.write(content);
            bos.flush();
        }
    }

    public static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedInputStream bis = new BufferedInputStream(fis);
            int ch;
            while ((ch = bis.read()) >= 0) {
                baos.write(ch);
            }
            baos.flush();
            return baos.toByteArray();
        }
    }

    private static final Random random = new Random();

    public static byte[] genRandomBytes(int len) {
        if (len <= 0) {
            throw new IllegalArgumentException("Length must > 0: " + len);
        }
        byte[] ret = new byte[len];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = (byte) random.nextInt(256);
        }
        return ret;
    }

}
