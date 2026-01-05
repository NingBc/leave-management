package com.leave.system.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 拼音转换工具类
 */
public class PinyinUtil {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    /**
     * 将中文转换为拼音
     * 
     * @param chinese 中文字符串
     * @return 拼音字符串（小写，无音调）
     */
    public static String toPinyin(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return "";
        }

        StringBuilder pinyin = new StringBuilder();
        char[] chars = chinese.toCharArray();

        for (char c : chars) {
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                // 是汉字
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        pinyin.append(pinyinArray[0]); // 取第一个读音（处理多音字）
                    } else {
                        pinyin.append(c);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    pinyin.append(c);
                }
            } else if (Character.isLetterOrDigit(c)) {
                // 保留英文和数字
                pinyin.append(Character.toLowerCase(c));
            }
            // 忽略其他字符（空格、标点等）
        }

        return pinyin.toString();
    }

    /**
     * 获取首字母
     * 
     * @param chinese 中文字符串
     * @return 首字母字符串（大写）
     */
    public static String getFirstLetters(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return "";
        }

        StringBuilder firstLetters = new StringBuilder();
        char[] chars = chinese.toCharArray();

        for (char c : chars) {
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        firstLetters.append(pinyinArray[0].charAt(0));
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    // Ignore
                }
            } else if (Character.isLetter(c)) {
                firstLetters.append(c);
            }
        }

        return firstLetters.toString().toUpperCase();
    }
}
