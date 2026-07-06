package com.example.inventory.context;

public class UserContext {
    private static final ThreadLocal<String> currentUser = ThreadLocal.withInitial(() -> "anonymous");

    public static String getCurrentUser() {
        return currentUser.get();
    }

    public static void setCurrentUser(String user) {
        currentUser.set(user);
    }

    public static void clear() {
        currentUser.remove();
    }
}
