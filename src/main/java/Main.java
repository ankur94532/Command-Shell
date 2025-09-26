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
                String[] files = convert(input); // extracts only quoted filenames
                if (files.length > 0) {
                    for (int i = 0; i < files.length; i++) {
                        content(files[i]); // your existing helper
                    }
                } else {
                    // Fallback: run external cat with unquoted args so "cat /tmp/x" works
                    java.util.List<String> args1 = tokenizeArgs(input.trim()); // already in your class
                    String exe = resolveOnPath(args1.get(0)); // robust PATH lookup
                    if (exe != null)
                        args1.set(0, exe);
                    ProcessBuilder pb = new ProcessBuilder(args);
                    pb.directory(new java.io.File(System.getProperty("user.dir")));
                    pb.inheritIO(); // stdout/stderr to terminal
                    Process p = pb.start();
                    p.waitFor();
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

    static void redirect(String input) throws IOException, InterruptedException {
        String[] inputs = input.split(" ");
        if (inputs[0].equals("echo")) {
            Deque<String> dq = new ArrayDeque<>();
            String dest = "";
            for (int i = 0; i < input.length();) {
                if (input.charAt(i) == '\'') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\'') {
                            i = j + 1;
                            break;
                        }
                        sb.append(input.charAt(j));
                        j++;
                    }
                    dq.offerLast(sb.toString());
                } else if (i + 1 < input.length() && input.charAt(i) == '1' && input.charAt(i + 1) == '>') {
                    dest = input.substring(i + 3);
                    break;
                } else if (input.charAt(i) == '>') {
                    dest = input.substring(i + 2);
                    break;
                }
            }
            dest = dest.trim();
            if ((dest.startsWith("\"") && dest.endsWith("\"")) ||
                    (dest.startsWith("'") && dest.endsWith("'"))) {
                dest = dest.substring(1, dest.length() - 1);
            }
            StringBuilder outBuf = new StringBuilder();
            for (var it = dq.iterator(); it.hasNext();) {
                outBuf.append(it.next());
                if (it.hasNext())
                    outBuf.append(' ');
            }
            outBuf.append('\n');
            Path outPath = Path.of(dest);
            Path parent = outPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(outPath,
                    outBuf.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else if (inputs[0].equals("ls")) {
            int gt = input.lastIndexOf('>');
            if (gt != -1) {
                String lhs = input.substring(0, gt).trim();

                String right = input.substring(gt + 1).trim();
                String dest = unquote(firstToken(right)); // handle quotes and extra spaces

                java.nio.file.Path out = java.nio.file.Path.of(dest);
                java.nio.file.Path parent = out.getParent();
                if (parent != null && !java.nio.file.Files.exists(parent)) {
                    java.nio.file.Files.createDirectories(parent);
                }

                String[] argv = lhs.split("\\s+"); // e.g. ["ls","-1","/tmp/qux"]
                String exe = resolveOnPath(argv[0]); // find "ls" in PATH
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new java.io.File(System.getProperty("user.dir")));
                    pb.redirectOutput(out.toFile()); // STDOUT → file (truncate/create)
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT); // STDERR → terminal
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();
                    p.waitFor();
                }
            } else {
                // no redirection: just run ls normally
                ProcessBuilder pb = new ProcessBuilder(input.trim().split("\\s+"));
                pb.directory(new java.io.File(System.getProperty("user.dir")));
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
            }
        } else if (inputs[0].equals("cat")) {
            int gt = input.lastIndexOf('>');
            if (gt == -1)
                return;

            // Handle optional "1>" (avoid confusing the option "-1")
            int j = gt - 1;
            while (j >= 0 && Character.isWhitespace(input.charAt(j)))
                j--;
            boolean oneRedir = (j >= 0 && input.charAt(j) == '1' && !(j - 1 >= 0 && input.charAt(j - 1) == '-'));

            String lhs = input.substring(0, oneRedir ? j : gt).trim(); // "cat ...sources..."
            String rhs = input.substring(gt + 1).trim(); // dest (maybe quoted)

            String dest = unquote(firstToken(rhs));
            Path out = Path.of(dest);
            Path parent = out.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent); // ensure parent dir exists
            }

            // Extract sources after "cat"
            String srcPart = lhs.startsWith("cat") ? lhs.substring(3).trim() : lhs;
            List<String> sources = tokenizeArgs(srcPart); // handles quotes/escapes

            // If no sources, cat would read stdin; here just create/truncate empty file
            if (sources.isEmpty()) {
                Files.write(out, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }

            // Exec external `cat` with stdout → file, stderr → terminal
            List<String> argv = new ArrayList<>();
            String catExe = resolveOnPath("cat");
            argv.add(catExe != null ? catExe : "cat");
            argv.addAll(sources);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(System.getProperty("user.dir"))); // honor your `cd`
            pb.redirectOutput(out.toFile()); // STDOUT → file
            pb.redirectError(ProcessBuilder.Redirect.INHERIT); // STDERR → terminal
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Process p = pb.start();
            p.waitFor();
        }

    }

    static String firstToken(String s) {
        // returns first whitespace-separated token (quotes handled by unquote)
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i)))
            i++;
        return s.substring(0, i);
    }

    static String unquote(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String resolveOnPath(String name) {
        if (name.contains(java.io.File.separator))
            return new java.io.File(name).getPath();
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
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

    static List<String> tokenizeArgs(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
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
