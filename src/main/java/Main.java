import java.util.Scanner;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage
        System.out.print("$ ");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            String str1 = input.substring(0, 4);
            if (str1.equals("type")) {
                String str = input.substring(5);
                if (str.equals("echo") || str.equals("exit") || str.equals("type")) {
                    System.out.println(str + " is a shell builtin");
                } else {
                    System.out.println(find(str));
                }
            } else if (str1.equals("echo")) {
                System.out.println(input.substring(5));
            } else if (str1.equals("exit")) {
                System.exit(0);
            } else {
                System.out.println(input + ": not found");
            }
            System.out.print("$ ");
        }
    }

    static String find(String str) {
        String path = System.getenv("PATH");
        String[] commands = path.split(":");
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists()) {
                return str + " is " + file.getAbsolutePath();
            }
        }
        return "";
    }
}
