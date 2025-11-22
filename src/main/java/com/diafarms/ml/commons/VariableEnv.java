package com.diafarms.ml.commons;

public class VariableEnv {
    // public static String get(String key) {
    //     return System.getenv(key);
    // }

    public static String get(String key) {
        return System.getProperty(key);
    }
}
