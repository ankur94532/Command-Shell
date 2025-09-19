import java.util.Scanner;

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
                    System.out.println(input.substring(5) + " is /bin/" + input.substring(5));
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
}
