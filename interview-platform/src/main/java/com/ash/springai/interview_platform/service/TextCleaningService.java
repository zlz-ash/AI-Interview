package com.ash.springai.interview_platform.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class TextCleaningService {
    
    private static final Pattern IMAGE_FILENAME_LINE =
            Pattern.compile("(?m)^image\\d+\\.(png|jpe?g|gif|bmp|webp)\\s*$");
    
    private static final Pattern IMAGE_URL = 
            Pattern.compile("https?://\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?",
                Pattern.CASE_INSENSITIVE
            );
    
    private static final Pattern FILE_URI =
                Pattern.compile("file:(//)?\\S+",Pattern.CASE_INSENSITIVE);
    
    private static final Pattern SEPARATOR_LINE =
                Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$");

    private static final Pattern CONTROL_CHARS =
                Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
        
    private static final Pattern HTML_TAGS =
                Pattern.compile("<[^>]+>");

    public String cleanText(String text){
        if(text == null || text.isBlank()){
            return "";
        }

        String t = text;

        t = CONTROL_CHARS.matcher(t).replaceAll("");
        t = IMAGE_FILENAME_LINE.matcher(t).replaceAll("");
        t = IMAGE_URL.matcher(t).replaceAll("");
        t = FILE_URI.matcher(t).replaceAll("");
        t = SEPARATOR_LINE.matcher(t).replaceAll("");

        t = t.replace("\r\n","\n").replace("\r","\n");

        t = t.replaceAll("(?m)[ \t]+$","");

        t = t.replaceAll("\\n{3,}","\n\n");

        return t.strip();
    }

    public String cleanTextWithLimit(String text,int maxLength){
        String cleaned = cleanText(text);
        if(cleaned.length() > maxLength){
            return cleaned.substring(0,maxLength);
        }
        return cleaned;
    }

    public String cleanToSingleLine(String text){
        if(text == null || text.isBlank()){
            return "";
        }
        return text
            .replaceAll("[\\r\\n]+"," ")
            .replaceAll("\\s+"," ")
            .strip();
    }

    public String stripHtml(String text){
        if(text == null || text.isBlank()){
            return "";
        }

        return HTML_TAGS.matcher(text).replaceAll(" ")
            .replaceAll("&nbsp;"," ")
            .replaceAll("&amp;","&")
            .replaceAll("&quot;","\"")
            .replaceAll("&apos;","'")
            .replaceAll("&lt;","<")
            .replaceAll("&gt;",">")
            .replaceAll("\\s+"," ")
            .strip();
    }
}
