import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage
        System.out.print("$ ");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            if (input.substring(0, 4).equals("type")) {
                String str = input.substring(5, 9);
                if (str.equals("echo") || str.equals("exit")) {
                    System.out.println(str + " is a shell bulletin");
                } else {
                    System.out.println(input + ": not found");
                }
            } else {
                System.out.println(input + ": not found");
            }
            System.out.print("$ ");
        }
    }
}
