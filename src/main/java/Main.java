import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
class TrieNode {
    TrieNode[] children = new TrieNode[128]; // For lowercase English letters
    boolean isEndOfWord = false; // Marks the end of a word
}
class Trie {
    private final TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    // Insert a word into the Trie
    public void insert(String word) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            if (current.children[index] == null) {
                current.children[index] = new TrieNode();
            }
            current = current.children[index];
        }
        current.isEndOfWord = true;
    }

    // Search for a word in the Trie
    public String search(String word) {
        StringBuilder result=new StringBuilder();
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            current = current.children[index];
        }
        while(true){
            if(current.isEndOfWord){
                break;
            }
            for(int i=0;i<128;i++){
                if(current.children[i]!=null){
                    result.append((char)i);
                    current=current.children[i];
                    break;
                }
            }
        }
        return result.toString();
    }
    public boolean checkComplete(String word){
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            current = current.children[index];
        }
        for(int i=0;i<128;i++){
            if(current.children[i]!=null){
                return false;
            }
        }
        return true;
    }
}
public class Main {
    public static void main(String[] args) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", "stty -echo -icanon min 1 < /dev/tty");
        processBuilder.directory(new File("").getCanonicalFile());
        Process rawMode = processBuilder.start();
        rawMode.waitFor();
        System.out.print("$ ");
        try (InputStreamReader inputStreamReader = new InputStreamReader(System.in);
                BufferedReader in = new BufferedReader(inputStreamReader)) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                Trie trie=new Trie();
                addToTrie(trie);
                boolean firstTab=false;
                sb.setLength(0);
                while (true) {
                    int ch = in.read();
                    if (ch == '\t') {
                        String str = sb.toString();
                        if (str.equals("e")) {
                            sb.append("cho ");
                            System.out.print("cho ");
                        } else if (str.equals("ec")) {
                            sb.append("ho ");
                            System.out.print("ho ");
                        } else if (str.equals("ech")) {
                            sb.append("o ");
                            System.out.print("o ");
                        } else if (str.equals("ex")) {
                            sb.append("it ");
                            System.out.print("it ");
                        } else if (str.equals("exi")) {
                            sb.append("t ");
                            System.out.print("t ");
                        } else {
                            String file=trie.search(str);
                            if(file.isEmpty()){
                                List<String>files = fileOnTab(str);
                                if(files.size()==1){
                                    sb.append(files.get(0).substring(str.length())+" ");
                                    System.out.print(files.get(0).substring(str.length())+" ");
                                    continue;
                                }
                                if(!firstTab){
                                    firstTab=true;
                                    System.out.println((char) 7);
                                    continue;
                                }
                                for(String file1:files){
                                    System.out.print(file1+"  ");
                                }
                                System.out.println();
                                System.out.print("$ "+sb.toString());
                                continue;
                            }
                            sb.append(file);
                            System.out.print(file);
                            if(trie.checkComplete(sb.toString())){
                                System.out.print(" ");
                                sb.append(" ");
                            }
                        }
                    } else if (ch == '\r' || ch == '\n') {
                        System.out.println();
                        break;
                    } else if (ch == 127 || ch == '\b') {
                        if (!sb.isEmpty()) {
                            System.out.print("\b \b");
                            sb.deleteCharAt(sb.length() - 1);
                        }
                    } else {
                        sb.append((char) ch);
                        System.out.print((char) ch);
                        System.out.flush();
                    }
                }
                String input = sb.toString();
                boolean flag = false;
                boolean error = false;
                boolean append = false;
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (input.charAt(i - 1) == '2') {
                            error = true;
                            input = input.substring(0, i - 1) + input.substring(i);
                        }
                        flag = true;
                        break;
                    }
                }
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                            append = true;
                            input = input.substring(0, i) + input.substring(i + 1);
                        }
                        flag = true;
                        break;
                    }
                }
                if (flag) {
                    redirect(input, error, append);
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
    }
    static void addToTrie(Trie trie){
        String path = System.getenv("PATH");
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory())
                continue;

            File[] matches = d.listFiles(f -> f.isFile() && f.canExecute());
            if (matches == null || matches.length == 0)
                continue;
            for(File file:matches){
                trie.insert(file.getName());
            }
        }
    }
    static void redirect(String input, boolean error, boolean append) throws IOException, InterruptedException {
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
                } else if (input.charAt(i) == '\"') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\"') {
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
                } else {
                    i++;
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
            Path outPath = Path.of(dest);
            Path parent = outPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            if (error) {
                System.out.println(outBuf.toString());
                if (append) {
                    Files.write(outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);// STDERR >> file
                } else {
                    Files.write(outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);// STDERR > file
                }
            } else {
                // > or >> dest
                if (append) {
                    outBuf.append('\n');
                    Files.write(outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND); // STDOUT >> file
                } else {
                    outBuf.append('\n');
                    Files.write(outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
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

                String[] argv = lhs.split("\\s+"); // e.g. ["ls","-1","/tmp/qux"
                String exe = resolveOnPath(argv[0]); // find "ls" in PATH
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));

                    if (error) {
                        // 2> or 2>> dest
                        if (append) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile())); // STDERR >> file
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.to(out.toFile())); // STDERR > file
                        }
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); // STDOUT → terminal
                    } else {
                        // > or >> dest
                        if (append) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile())); // STDOUT >> file
                        } else {
                            pb.redirectOutput(out.toFile()); // STDOUT > file
                        }
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT); // STDERR → terminal
                    }

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
            if (error) {
                // 2> or 2>> dest
                if (append) {
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile())); // STDERR >> file
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.to(out.toFile())); // STDERR > file
                }
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); // STDOUT → terminal
            } else {
                // > or >> dest
                if (append) {
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile())); // STDOUT >> file
                } else {
                    pb.redirectOutput(out.toFile()); // STDOUT > file
                }
                pb.redirectError(ProcessBuilder.Redirect.INHERIT); // STDERR → terminal
            }
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

    static List<String> fileOnTab(String str) {
        List<String>files=new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty())
            return null;

        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory())
                continue;

            File[] matches = d.listFiles(f -> f.isFile() && f.getName().startsWith(str) && f.canExecute());
            if (matches == null || matches.length == 0)
                continue;
            for(File file:matches){
                files.add(file.getName());
            }
        }
        Collections.sort(files);
        return files;
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
            if (input.charAt(ind) == '/') {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && (input.charAt(ind) != ' ')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && (input.charAt(ind) != '\'')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\"') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && (input.charAt(ind) != '\"')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            }
            ind++;
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