import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // Uncomment this block to pass the first stage
        System.out.print("$ ");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            boolean flag = false;
            for (int i = 0; i < input.length(); i++) {
                if (input.charAt(i) == '>') {
                    flag = true;
                    break;
                }
            }
            if (flag) {
                redirect(input);
                System.out.print("$ ");
                continue;
            }
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
            if (input.charAt(0) == '\'') {
                int ind = 1;
                for (int i = 1; i < input.length(); i++) {
                    if (input.charAt(i) == '\'') {
                        ind = i;
                        break;
                    }
                }
                content(input.substring(ind + 2));
                System.out.print("$ ");
                continue;
            }
            if (input.charAt(0) == '"') {
                int ind = 1;
                for (int i = 1; i < input.length(); i++) {
                    if (input.charAt(i) == '"') {
                        ind = i;
                        break;
                    }
                }
                content(input.substring(ind + 2));
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
                if (str.equals("echo")
                        || str.equals("exit")
                        || str.equals("pwd")
                        || str.equals("type")) {
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

    static void redirect(String input) throws Exception {
        int gt = input.indexOf('>');
        if (gt == -1)
            return;

        String lhs = input.substring(0, gt).trim();

        // Support optional "1>" (stdout), without confusing "-1"
        int j = gt - 1;
        while (j >= 0 && Character.isWhitespace(input.charAt(j)))
            j--;
        if (j >= 0 && input.charAt(j) == '1') {
            // Only treat as 1> if the preceding char isn't '-' (to avoid "-1")
            if (!(j - 1 >= 0 && input.charAt(j - 1) == '-')) {
                lhs = input.substring(0, j).trim();
            }
        }

        String right = input.substring(gt + 1).trim();
        if (right.isEmpty())
            return;

        // Allow quoted filenames
        if ((right.startsWith("\"") && right.endsWith("\"")) ||
                (right.startsWith("'") && right.endsWith("'"))) {
            right = right.substring(1, right.length() - 1);
        }

        Path out = Path.of(right);
        Path parent = out.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent); // ensure /tmp/bar exists
        }

        // Built-ins that write to stdout
        if (lhs.startsWith("echo")) {
            String payload = (lhs.length() >= 5 ? print(lhs.substring(5)) : "") + "\n";
            Files.write(out, payload.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        if (lhs.equals("pwd") || lhs.startsWith("pwd ")) {
            String payload = System.getProperty("user.dir") + "\n";
            Files.write(out, payload.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }

        // External commands (e.g., ls)
        String[] argv = lhs.isEmpty() ? new String[0] : lhs.split("\\s+");
        if (argv.length == 0)
            return;

        // Resolve executable on PATH to avoid env quirks
        String exe = resolveOnPath(argv[0]);
        if (exe == null) {
            System.out.println(argv[0] + ": not found");
            return;
        }
        argv[0] = exe;

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(new File(System.getProperty("user.dir"))); // honor 'cd'
        pb.redirectOutput(out.toFile()); // stdout → file
        pb.redirectError(ProcessBuilder.Redirect.INHERIT); // stderr → terminal
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();
        p.waitFor();
    }

    static String resolveOnPath(String name) {
        if (name.contains(File.separator))
            return new File(name).getPath();
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute())
                return f.getAbsolutePath();
        }
        return null;
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
                    if (input.charAt(ind) == '\\' && ind + 1 < input.length()) {
                        sb.append(input.charAt(ind + 1));
                        ind += 2;
                        continue;
                    }
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
                dq.offerLast(new StringBuilder("").append(input.charAt(ind + 1)).toString());
                ind += 2;
                continue;
            } else if (input.charAt(ind) == ' ') {
                if (dq.size() > 0 && dq.peekLast().equals(" ")) {
                    ind++;
                    continue;
                }
                dq.offerLast(" ");
                ind++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && input.charAt(ind) != ' '
                        && input.charAt(ind) != '\''
                        && input.charAt(ind) != '"'
                        && input.charAt(ind) != '\\') {
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
