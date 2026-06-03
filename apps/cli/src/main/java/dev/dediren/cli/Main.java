package dev.dediren.cli;

public final class Main {
    private Main() {
    }

    public static String moduleName() {
        return "cli";
    }

    public static void main(String[] args) {
        System.out.println(moduleName());
    }
}
