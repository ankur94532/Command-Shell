import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Scanner;
import java.io.File;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage
        System.out.print("$ ");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            if (input.split(" ")[0].equals("cd")) {
                String path = input.split(" ")[1];
                if (path.charAt(0) == '.') {
                    change(path);
                    System.out.print("$ ");
                    continue;
                }
                path = exists(path);
                if (path != null) {
                    System.setProperty("user.dir", path);
                } else {
                    System.out.println(
                            "cd: " + input.split(" ")[1] + ": No such file or directory");
                }
                System.out.print("$ ");
                continue;
            }
            if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                System.out.print("$ ");
                continue;
            }
            if (input.split(" ")[0].equals("pwd")) {
                System.out.println(Path.of("").toAbsolutePath());
                System.out.print("$ ");
                continue;
            }
            if (check(input)) {
                Process process = Runtime.getRuntime().exec(input.split(" "));
                process.getInputStream().transferTo(System.out);
                System.out.print("$ ");
                continue;
            }
            String str1 = input.substring(0, 4);
            if (str1.equals("type")) {
                String str = input.substring(5);
                if (str.equals("echo") || str.equals("exit") || str.equals("pwd") || str.equals("type")) {
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

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) {
            dq.offer(paths[i]);
        }
        System.out.println("hi" + dq.peekFirst());
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder("");
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder("");
        while (dq.size() > 0) {
            sb.append('/');
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        System.setProperty("user.dir", sb.toString());
    }

    static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory()) {
            return file.getAbsolutePath();
        }
        return null;
    }

    static boolean check(String input) {
        String[] inputs = input.split(" ");
        String path = System.getenv("PATH");
        String[] commands = path.split(":");
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], inputs[0]);
            if (file.exists() && file.canExecute()) {
                return true;
            }
        }
        return false;
    }

    static String find(String str) {
        String path = System.getenv("PATH");
        String[] commands = path.split(":");
        String ans = str + ": not found";
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists() && file.canExecute()) {
                return str + " is " + file.getAbsolutePath();
            }
        }
        return ans;
    }
}
