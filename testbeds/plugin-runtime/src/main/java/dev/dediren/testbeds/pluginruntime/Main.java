package dev.dediren.testbeds.pluginruntime;

public final class Main {
    private Main() {
    }

    public static String moduleName() {
        return "plugin-runtime-testbed";
    }

    public static void main(String[] args) {
        System.out.println(moduleName());
    }
}
