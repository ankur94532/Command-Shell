import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage

        while (true) {
            Scanner scanner = new Scanner(System.in);
            System.out.print("$ ");
            String input = scanner.nextLine();
            System.out.println(input + ": command not found");
            scanner.close();
        }
    }
}
