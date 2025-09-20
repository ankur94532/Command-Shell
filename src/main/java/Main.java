import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Scanner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage
        System.out.print("$ ");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            if (input.split(" ")[0].equals("echo")) {
                System.out.println(print(input.substring(5)));
                System.out.print("$ ");
                continue;
            }
            if (input.split(" ")[0].equals("cat")) {
                String[] files = convert(input);
                for (int i = 0; i < files.length; i++) {
                    content(files[i]);
                }

                System.out.print("$ ");
                continue;
            }
            if (input.split(" ")[0].equals("cd")) {
                String path = input.split(" ")[1];
                if (path.charAt(0) == '.') {
                    change(path);
                    System.out.print("$ ");
                    continue;
                }
                if (path.charAt(0) == '~') {
                    System.setProperty("user.dir", System.getenv("HOME"));
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

    static String[] convert(String input) {
        Deque<String> response = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '\'') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '"') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (input.charAt(ind) != '"') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                response.offerLast(sb.toString());
            } else {
                ind++;
            }
        }
        return response.toArray(new String[response.size()]);
    }

    static void content(String file) {
        Process p;
        try {
            p = new ProcessBuilder("cat", file)
                    .redirectErrorStream(true) // merge stderr into stdout
                    .start();
            p.getInputStream().transferTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String print(String input) {
        Deque<String> dq = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '"') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '"') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\\') {
                if (ind + 1 < input.length() && input.charAt(ind + 1) == ' ') {
                    dq.offerLast(" ");
                    ind += 2;
                    continue;
                }
            } else if (input.charAt(ind) == ' ') {
                if (dq.size() > 0 && dq.peekLast().equals(" ")) {
                    ind++;
                    continue;
                }
                dq.offerLast(" ");
                ind++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != ' ' && input.charAt(ind) != '\''
                        && input.charAt(ind) != '"' && input.charAt(ind) != '\\') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offerLast(sb.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        while (dq.size() > 0) {
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        return sb.toString();
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) {
            dq.offer(paths[i]);
        }
        dq.pollFirst();
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
