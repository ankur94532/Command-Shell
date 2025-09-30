import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

interface Proc {
    OutputStream stdin();

    InputStream stdout();

    InputStream stderr();

    void start() throws Exception;

    int waitFor() throws InterruptedException;
}

final class ExternalProc implements Proc {
    private final ProcessBuilder pb;
    private Process p;

    ExternalProc(List<String> argv, File cwd, Map<String, String> env) {
        this.pb = new ProcessBuilder(argv).directory(cwd);
        if (env != null)
            pb.environment().putAll(env);
    }

    @Override
    public OutputStream stdin() {
        return p.getOutputStream();
    }

    @Override
    public InputStream stdout() {
        return p.getInputStream();
    }

    @Override
    public InputStream stderr() {
        return p.getErrorStream();
    }

    @Override
    public void start() throws IOException {
        p = pb.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return p.waitFor();
    }
}

final class BuiltinProc implements Proc {
    private final String name;
    private final List<String> args;
    private final Builtins impl;
    private final PipedOutputStream inWriter = new PipedOutputStream();
    private final PipedInputStream inReader;
    private final PipedOutputStream outWriter = new PipedOutputStream();
    private final PipedInputStream outReader;
    private final PipedOutputStream errWriter = new PipedOutputStream();
    private final PipedInputStream errReader;

    private Thread worker;
    private volatile int exitCode = 0;

    BuiltinProc(String name, List<String> args, Builtins impl) throws IOException {
        this.name = name;
        this.args = args;
        this.impl = impl;
        this.inReader = new PipedInputStream(inWriter);
        this.outReader = new PipedInputStream(outWriter);
        this.errReader = new PipedInputStream(errWriter);
    }

    @Override
    public OutputStream stdin() {
        return inWriter;
    }

    @Override
    public InputStream stdout() {
        return outReader;
    }

    @Override
    public InputStream stderr() {
        return errReader;
    }

    @Override
    public void start() {
        worker = new Thread(() -> {
            try {
                exitCode = impl.runBuiltin(name, args, inReader, outWriter, errWriter);
            } catch (Exception e) {
                try {
                    errWriter.write(("internal error: " + e.getMessage() + "\n").getBytes());
                } catch (IOException ignore) {
                }
                exitCode = 1;
            } finally {
                Main.IO.closeQuietly(outWriter);
                Main.IO.closeQuietly(errWriter);
                Main.IO.closeQuietly(inReader);
            }
        }, "builtin-" + name);
        worker.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        worker.join();
        return exitCode;
    }
}

final class Builtins {
    private static boolean isBuiltin(String s) {
        return s.equals("echo") || s.equals("exit") || s.equals("pwd")
                || s.equals("type") || s.equals("cd") || s.equals("history");
    }

    static String findOnPath(String str) {
        String path = System.getenv("PATH");
        if (path == null)
            return str + ": not found";
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, str);
            if (f.exists() && f.canExecute()) {
                return str + " is " + f.getAbsolutePath();
            }
        }
        return str + ": not found";
    }

    int runBuiltin(String name, List<String> args,
            InputStream in, OutputStream out, OutputStream err) throws IOException {
        switch (name) {
            case "echo": {
                out.write(String.join(" ", args).getBytes());
                out.write('\n');
                out.flush();
                return 0;
            }
            case "pwd": {
                out.write((System.getProperty("user.dir") + "\n").getBytes());
                out.flush();
                return 0;
            }
            case "type": {
                if (args.isEmpty())
                    return 0;
                int rc = 0;
                for (String target : args) {
                    if (isBuiltin(target)) {
                        out.write((target + " is a shell builtin\n").getBytes());
                    } else {
                        String line = findOnPath(target);
                        if (line.endsWith("not found")) {
                            out.write((target + " not found\n").getBytes());
                            rc = 1;
                        } else {
                            out.write((line + "\n").getBytes());
                        }
                    }
                }
                out.flush();
                return rc;
            }
            case "cd": {
                if (args.isEmpty()) {
                    String home = System.getenv("HOME");
                    if (home == null || home.isEmpty())
                        home = System.getProperty("user.home");
                    if (home != null && !home.isEmpty()) {
                        System.setProperty("user.dir", home);
                        return 0;
                    }
                    err.write("cd: HOME not set\n".getBytes());
                    err.flush();
                    return 1;
                }
                if (args.size() > 1) {
                    err.write("cd: too many arguments\n".getBytes());
                    err.flush();
                    return 1;
                }

                String pathArg = args.get(0);
                if (!pathArg.isEmpty() && pathArg.charAt(0) == '~') {
                    String home = System.getenv("HOME");
                    if (home == null || home.isEmpty())
                        home = System.getProperty("user.home");
                    if (home != null && !home.isEmpty()) {
                        System.setProperty("user.dir", home);
                        return 0;
                    }
                    err.write("cd: HOME not set\n".getBytes());
                    err.flush();
                    return 1;
                }
                if (!pathArg.isEmpty() && pathArg.charAt(0) == '.') {
                    Main.change(pathArg);
                    return 0;
                }
                String abs = Main.exists(pathArg);
                if (abs != null) {
                    System.setProperty("user.dir", abs);
                    return 0;
                }
                String relTry = Main.exists(System.getProperty("user.dir") + "/" + pathArg);
                if (relTry != null) {
                    System.setProperty("user.dir", relTry);
                    return 0;
                }
                err.write(("cd: " + pathArg + ": No such file or directory\n").getBytes());
                err.flush();
                return 1;
            }
            default: {
                err.write((name + ": not a builtin\n").getBytes());
                err.flush();
                return 1;
            }
        }
    }
}

class TrieNode {
    TrieNode[] children = new TrieNode[128];
    boolean isEndOfWord = false;
}

class Trie {
    private final TrieNode root = new TrieNode();

    public void insert(String word) {
        TrieNode cur = root;
        for (char ch : word.toCharArray()) {
            int idx = ch;
            if (cur.children[idx] == null)
                cur.children[idx] = new TrieNode();
            cur = cur.children[idx];
        }
        cur.isEndOfWord = true;
    }

    public String search(String word) {
        StringBuilder result = new StringBuilder();
        TrieNode cur = root;
        for (char ch : word.toCharArray()) {
            int idx = ch;
            if (cur.children[idx] == null)
                return "";
            cur = cur.children[idx];
        }
        while (!cur.isEndOfWord) {
            int c = 0;
            int next = -1;
            for (int i = 0; i < 128; i++)
                if (cur.children[i] != null) {
                    c++;
                    next = i;
                }
            if (c != 1)
                return "";
            result.append((char) next);
            cur = cur.children[next];
        }
        return result.toString();
    }

    public boolean checkComplete(String word) {
        TrieNode cur = root;
        for (char ch : word.toCharArray())
            cur = cur.children[ch];
        for (int i = 0; i < 128; i++)
            if (cur.children[i] != null)
                return false;
        return true;
    }
}

public class Main {

    static final String PROMPT = "$ ";

    static final int KEY_UP = -1001, KEY_DOWN = -1002, KEY_RIGHT = -1003, KEY_LEFT = -1004,
            KEY_ENTER = -1005, KEY_BACKSPACE = -1006;

    static class ANSI {
        static void clearLine() {
            System.out.print("\r\u001B[2K");
        }

        static void redraw(String prompt, CharSequence buf) {
            clearLine();
            System.out.print(prompt);
            System.out.print(buf);
            System.out.flush();
        }
    }

    static class History {
        private final ArrayList<String> entries = new ArrayList<>();
        private int cursor = -1; // -1 => new line

        void loadFrom(Path file) throws IOException {
            if (file != null && Files.exists(file)) {
                try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null)
                        if (!line.isEmpty())
                            entries.add(line);
                }
            }
        }

        void saveAll(Path file) throws IOException {
            if (file == null)
                return;
            StringBuilder buf = new StringBuilder();
            for (String s : entries)
                buf.append(s).append('\n');
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            Files.writeString(file, buf.toString(), StandardCharsets.UTF_8);
        }

        void appendNew(Path file, int fromIndex) throws IOException {
            if (file == null)
                return;
            StringBuilder buf = new StringBuilder();
            for (int i = fromIndex; i < entries.size(); i++)
                buf.append(entries.get(i)).append('\n');
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            Files.writeString(file, buf.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        void add(String s) {
            if (!s.isBlank())
                entries.add(s);
            cursor = -1;
        }

        int size() {
            return entries.size();
        }

        String get(int i) {
            return entries.get(i);
        }

        String prev() {
            if (entries.isEmpty())
                return null;
            if (cursor == -1)
                cursor = entries.size() - 1;
            else if (cursor > 0)
                cursor--;
            return entries.get(cursor);
        }

        String next() {
            if (entries.isEmpty())
                return null;
            if (cursor >= 0 && cursor < entries.size() - 1) {
                cursor++;
                return entries.get(cursor);
            }
            cursor = -1;
            return "";
        }
    }

    static class IO {
        static void closeQuietly(Closeable c) {
            try {
                if (c != null)
                    c.close();
            } catch (IOException ignored) {
            }
        }
    }

    static class PathUtil {
        static String resolveOnPath(String name) {
            if (name.contains(File.separator))
                return new File(name).getAbsolutePath();
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

        static boolean isOnPath(String name) {
            String exe = resolveOnPath(name);
            return exe != null && new File(exe).canExecute();
        }
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

    static void pushTok(List<String> cur, StringBuilder tok) {
        if (tok.length() > 0) {
            cur.add(tok.toString());
            tok.setLength(0);
        }
    }

    static List<List<String>> splitPipeline(String s) {
        ArrayList<List<String>> out = new ArrayList<>();
        ArrayList<String> cur = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        boolean inS = false, inD = false, esc = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                tok.append(c);
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

            if (c == '|' && !inS && !inD) {
                pushTok(cur, tok);
                if (!cur.isEmpty())
                    out.add(new ArrayList<>(cur));
                cur.clear();
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                pushTok(cur, tok);
                continue;
            }
            tok.append(c);
        }
        pushTok(cur, tok);
        if (!cur.isEmpty())
            out.add(cur);
        return out;
    }

    static int readKey(java.io.PushbackInputStream in) throws java.io.IOException {
        int b = in.read();
        if (b == -1)
            return -1;

        if (b == 0x1B) { // ESC
            int b1 = in.read();
            if (b1 == -1)
                return 0x1B;
            if (b1 == '[' || b1 == 'O') {
                int b2 = in.read();
                if (b2 == -1)
                    return 0x1B;
                switch (b2) {
                    case 'A':
                        return KEY_UP;
                    case 'B':
                        return KEY_DOWN;
                    case 'C':
                        return KEY_RIGHT;
                    case 'D':
                        return KEY_LEFT;
                    default:
                        if (Character.isDigit(b2)) {
                            int x;
                            while ((x = in.read()) != -1 && Character.isDigit(x)) {
                            }
                            if (x != '~' && x != -1)
                                in.unread(x);
                        }
                        return 0x1B;
                }
            } else {
                in.unread(b1);
                return 0x1B;
            }
        } else if (b == 127)
            return KEY_BACKSPACE;
        else if (b == '\r' || b == '\n')
            return KEY_ENTER;

        return b;
    }

    public static void main(String[] args) throws Exception {
        if (System.console() != null) {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "stty -echo -icanon min 1 < /dev/tty");
            pb.directory(new File("").getCanonicalFile());
            pb.start().waitFor();
        }

        Builtins builtins = new Builtins();
        History history = new History();
        Map<Path, Integer> appendTracker = new HashMap<>();

        Path histFile = System.getenv().containsKey("HISTFILE") ? Path.of(System.getenv("HISTFILE")) : null;
        if (histFile != null)
            history.loadFrom(histFile);

        System.out.print(PROMPT);

        try (java.io.PushbackInputStream pin = new java.io.PushbackInputStream(System.in, 8)) {
            StringBuilder sb = new StringBuilder();

            while (true) {
                Trie trie = new Trie();
                addToTrie(trie);
                boolean firstTab = false;
                sb.setLength(0);

                while (true) {
                    int ch = readKey(pin);
                    if (ch == -1)
                        return;

                    if (ch == KEY_UP) {
                        String prev = history.prev();
                        if (prev != null) {
                            sb.setLength(0);
                            sb.append(prev);
                            ANSI.redraw(PROMPT, sb);
                        }
                        continue;
                    } else if (ch == KEY_DOWN) {
                        String next = history.next();
                        if (next != null) {
                            sb.setLength(0);
                            sb.append(next);
                            ANSI.redraw(PROMPT, sb);
                        }
                        continue;
                    } else if (ch == '\t') {
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
                            String file = trie.search(str);
                            if (file.isEmpty()) {
                                List<String> files = fileOnTab(str);
                                if (files == null || files.isEmpty()) {
                                    System.out.println((char) 7);
                                    continue;
                                }
                                if (files.size() == 1) {
                                    String tail = files.get(0).substring(str.length()) + " ";
                                    sb.append(tail);
                                    System.out.print(tail);
                                    continue;
                                }
                                if (!firstTab) {
                                    firstTab = true;
                                    System.out.println((char) 7);
                                    continue;
                                }
                                for (String f : files)
                                    System.out.print(f + "  ");
                                System.out.println();
                                ANSI.redraw(PROMPT, sb);
                                continue;
                            }
                            sb.append(file);
                            System.out.print(file);
                            if (trie.checkComplete(sb.toString())) {
                                System.out.print(" ");
                                sb.append(" ");
                            }
                        }
                        System.out.flush();
                        continue;
                    } else if (ch == KEY_ENTER) {
                        System.out.println();
                        break;
                    } else if (ch == KEY_BACKSPACE) {
                        if (sb.length() > 0) {
                            sb.deleteCharAt(sb.length() - 1);
                            ANSI.redraw(PROMPT, sb);
                        }
                        continue;
                    } else {
                        if (ch >= 32 && ch < 127) {
                            sb.append((char) ch);
                            System.out.print((char) ch);
                            System.out.flush();
                        }
                    }
                }

                String input = sb.toString();
                history.add(input);

                if (input.equals("history")) {
                    for (int i = 0; i < history.size(); i++)
                        System.out.println((i + 1) + " " + history.get(i));
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.startsWith("history")) {
                    String[] parts = input.split("\\s+");
                    if (parts.length > 1 && parts[1].equals("-r") && parts.length >= 3) {
                        // read history from file
                        Path p = Path.of(parts[2]);
                        history.loadFrom(p);
                        System.out.print(PROMPT);
                        continue;
                    }
                    if (parts.length > 1 && parts[1].equals("-w") && parts.length >= 3) {
                        Path p = Path.of(parts[2]);
                        history.saveAll(p);
                        System.out.print(PROMPT);
                        continue;
                    }
                    if (parts.length > 1 && parts[1].equals("-a") && parts.length >= 3) {
                        Path p = Path.of(parts[2]);
                        int from = appendTracker.getOrDefault(p, 0);
                        history.appendNew(p, from);
                        appendTracker.put(p, history.size());
                        System.out.print(PROMPT);
                        continue;
                    }
                    if (parts.length > 1) {
                        int n = Integer.parseInt(parts[1]);
                        for (int i = Math.max(0, history.size() - n); i < history.size(); i++) {
                            System.out.println((i + 1) + " " + history.get(i));
                        }
                        System.out.print(PROMPT);
                        continue;
                    }
                }

                if (splitPipeline(input).size() > 1) {
                    usePipe(input, builtins);
                    System.out.print(PROMPT);
                    continue;
                }
                boolean hasRedir = false, errRedir = false, append = false;
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i - 1 >= 0 && input.charAt(i - 1) == '2') {
                            errRedir = true;
                            input = input.substring(0, i - 1) + input.substring(i);
                        }
                        hasRedir = true;
                        break;
                    }
                }
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                            append = true;
                            input = input.substring(0, i) + input.substring(i + 1);
                        }
                        hasRedir = true;
                        break;
                    }
                }
                if (hasRedir) {
                    redirect(input, errRedir, append);
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.split(" ")[0].equals("echo")) {
                    System.out.println(print(input.substring(5)));
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.split(" ")[0].equals("cat")) {
                    String[] files = convert(input);
                    if (files.length > 0)
                        for (String f : files)
                            content(f);
                    System.out.print(PROMPT);
                    continue;
                }
                if (!input.isEmpty() && input.charAt(0) == '\'') {
                    int ind = input.indexOf('\'', 1);
                    if (ind != -1 && ind + 2 <= input.length())
                        content(input.substring(ind + 2));
                    System.out.print(PROMPT);
                    continue;
                }
                if (!input.isEmpty() && input.charAt(0) == '"') {
                    int ind = input.indexOf('"', 1);
                    if (ind != -1 && ind + 2 <= input.length())
                        content(input.substring(ind + 2));
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.split(" ")[0].equals("cd")) {
                    String[] parts = input.split("\\s+", 2);
                    if (parts.length >= 2) {
                        String path = parts[1];
                        if (path.charAt(0) == '.') {
                            change(path);
                            System.out.print(PROMPT);
                            continue;
                        }
                        if (path.charAt(0) == '~') {
                            System.setProperty("user.dir", System.getenv("HOME"));
                            System.out.print(PROMPT);
                            continue;
                        }
                        String ok = exists(path);
                        if (ok != null)
                            System.setProperty("user.dir", ok);
                        else
                            System.out.println("cd: " + path + ": No such file or directory");
                    }
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.equals("pwd")) {
                    System.out.println(System.getProperty("user.dir"));
                    System.out.print(PROMPT);
                    continue;
                }
                if (input.split(" ")[0].equals("pwd")) {
                    System.out.println(Path.of("").toAbsolutePath());
                    System.out.print(PROMPT);
                    continue;
                }

                // external command (non-pipe)
                if (checkExternal(input)) {
                    List<String> argv = tokenizeArgs(input);
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    Process proc = pb.start();
                    proc.getInputStream().transferTo(System.out);
                    System.out.print(PROMPT);
                    continue;
                }

                // minimal `type`/`exit`/fallback
                String head4 = input.length() >= 4 ? input.substring(0, 4) : input;
                if (head4.equals("type")) {
                    String str = input.length() > 5 ? input.substring(5) : "";
                    if (str.equals("echo") || str.equals("exit") || str.equals("pwd") || str.equals("type")
                            || str.equals("history")) {
                        System.out.println(str + " is a shell builtin");
                    } else {
                        System.out.println(Builtins.findOnPath(str));
                    }
                } else if (head4.equals("echo")) {
                    System.out.println(input.substring(5));
                } else if (head4.equals("exit")) {
                    if (histFile != null)
                        history.saveAll(histFile);
                    System.exit(0);
                } else {
                    System.out.println(input + ": not found");
                }
                System.out.print(PROMPT);
            }
        }
    }

    static void usePipe(String input, Builtins sharedBuiltins) throws Exception {
        List<List<String>> segments = splitPipeline(input);
        List<Proc> processes = new ArrayList<>();

        for (List<String> seg : segments) {
            if (seg.isEmpty())
                continue;
            String cmd = seg.get(0);
            if (cmd.equals("echo") || cmd.equals("type") || cmd.equals("cd") || cmd.equals("pwd")) {
                processes.add(new BuiltinProc(cmd, seg.subList(1, seg.size()), sharedBuiltins));
            } else {
                processes.add(new ExternalProc(seg, new File(System.getProperty("user.dir")), System.getenv()));
            }
        }
        startPipe(processes);
    }

    static int startPipe(List<Proc> ps) throws Exception {
        if (ps.isEmpty())
            return 0;
        for (Proc p : ps)
            p.start();

        List<Thread> pumps = new ArrayList<>();
        for (int i = 0; i < ps.size() - 1; i++) {
            pumps.add(pump(ps.get(i).stdout(), ps.get(i + 1).stdin(), true));
        }
        Thread lastOut = pump(ps.get(ps.size() - 1).stdout(), System.out, false);

        List<Thread> errPumps = new ArrayList<>();
        for (Proc p : ps)
            errPumps.add(pump(p.stderr(), System.err, false));

        IO.closeQuietly(ps.get(0).stdin());

        int code = 0;
        for (Proc p : ps)
            code = p.waitFor();
        for (Thread t : pumps)
            t.join();
        lastOut.join();
        for (Thread t : errPumps)
            t.join();
        return code;
    }

    static Thread pump(InputStream in, OutputStream out, boolean closeDest) {
        Thread t = new Thread(() -> {
            final byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                if (closeDest)
                    IO.closeQuietly(out);
                IO.closeQuietly(in);
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void redirect(String input, boolean error, boolean append) throws IOException, InterruptedException {
        String[] inputs = input.split(" ");
        if (inputs[0].equals("echo")) {
            // parse echo args + destination
            Deque<String> dq = new ArrayDeque<>();
            String dest = "";
            for (int i = 0; i < input.length();) {
                if (input.charAt(i) == '\'') {
                    int j = ++i;
                    StringBuilder sb = new StringBuilder();
                    while (j < input.length() && input.charAt(j) != '\'')
                        sb.append(input.charAt(j++));
                    dq.offerLast(sb.toString());
                    i = Math.min(input.length(), j + 1);
                } else if (input.charAt(i) == '\"') {
                    int j = ++i;
                    StringBuilder sb = new StringBuilder();
                    while (j < input.length() && input.charAt(j) != '\"')
                        sb.append(input.charAt(j++));
                    dq.offerLast(sb.toString());
                    i = Math.min(input.length(), j + 1);
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
            if ((dest.startsWith("\"") && dest.endsWith("\"")) || (dest.startsWith("'") && dest.endsWith("'"))) {
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
            if (parent != null && !Files.exists(parent))
                Files.createDirectories(parent);

            if (error) {
                if (append)
                    Files.write(outPath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                else
                    Files.write(outPath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                outBuf.append('\n');
                if (append)
                    Files.write(outPath, outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                else
                    Files.write(outPath, outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return;
        }

        if (inputs[0].equals("ls")) {
            int gt = input.lastIndexOf('>');
            if (gt != -1) {
                String lhs = input.substring(0, gt).trim();
                String right = input.substring(gt + 1).trim();
                String dest = unquote(firstToken(right));
                Path out = Path.of(dest);
                Path parent = out.getParent();
                if (parent != null && !Files.exists(parent))
                    Files.createDirectories(parent);

                String[] argv = lhs.split("\\s+");
                String exe = PathUtil.resolveOnPath(argv[0]);
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    if (error) {
                        if (append)
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else
                            pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        if (append)
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else
                            pb.redirectOutput(out.toFile());
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();
                    p.waitFor();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder(input.trim().split("\\s+"));
                pb.directory(new File(System.getProperty("user.dir")));
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
            }
            return;
        }

        if (inputs[0].equals("cat")) {
            int gt = input.lastIndexOf('>');
            if (gt == -1)
                return;
            int j = gt - 1;
            while (j >= 0 && Character.isWhitespace(input.charAt(j)))
                j--;
            boolean oneRedir = (j >= 0 && input.charAt(j) == '1' && !(j - 1 >= 0 && input.charAt(j - 1) == '-'));
            String lhs = input.substring(0, oneRedir ? j : gt).trim();
            String rhs = input.substring(gt + 1).trim();
            String dest = unquote(firstToken(rhs));
            Path out = Path.of(dest);
            Path parent = out.getParent();
            if (parent != null && !Files.exists(parent))
                Files.createDirectories(parent);

            String srcPart = lhs.startsWith("cat") ? lhs.substring(3).trim() : lhs;
            List<String> sources = tokenizeArgs(srcPart);
            if (sources.isEmpty()) {
                Files.write(out, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }

            List<String> argv = new ArrayList<>();
            String catExe = PathUtil.resolveOnPath("cat");
            argv.add(catExe != null ? catExe : "cat");
            argv.addAll(sources);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(System.getProperty("user.dir")));
            if (error) {
                if (append)
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else
                    pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            } else {
                if (append)
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else
                    pb.redirectOutput(out.toFile());
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.waitFor();
        }
    }

    static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i)))
            i++;
        return s.substring(0, i);
    }

    static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            return s.substring(1, s.length() - 1);
        return s;
    }

    static void addToTrie(Trie trie) {
        String path = System.getenv("PATH");
        if (path == null)
            return;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory())
                continue;
            File[] matches = d.listFiles(f -> f.isFile() && f.canExecute());
            if (matches == null)
                continue;
            for (File f : matches)
                trie.insert(f.getName());
        }
    }

    static List<String> fileOnTab(String str) {
        List<String> files = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty())
            return files;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory())
                continue;
            File[] matches = d.listFiles(f -> f.isFile() && f.getName().startsWith(str) && f.canExecute());
            if (matches == null)
                continue;
            for (File f : matches)
                files.add(f.getName());
        }
        Collections.sort(files);
        return files;
    }

    static Path getPathForFile(String name) {
        return Path.of(name);
    }

    static String resolveOnPath(String name) {
        return PathUtil.resolveOnPath(name);
    }

    static String[] convert(String input) {
        Deque<String> out = new ArrayDeque<>();
        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '/') {
                StringBuilder sb = new StringBuilder();
                while (i < input.length() && input.charAt(i) != ' ')
                    sb.append(input.charAt(i++));
                out.offerLast(sb.toString());
            } else if (input.charAt(i) == '\'') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < input.length() && input.charAt(i) != '\'')
                    sb.append(input.charAt(i++));
                out.offerLast(sb.toString());
            } else if (input.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < input.length() && input.charAt(i) != '"')
                    sb.append(input.charAt(i++));
                out.offerLast(sb.toString());
            }
            i++;
        }
        return out.toArray(new String[0]);
    }

    static void content(String file) {
        try {
            Process p = new ProcessBuilder("cat", file).redirectErrorStream(true).start();
            p.getInputStream().transferTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String print(String input) {
        Deque<String> dq = new ArrayDeque<>();
        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i != input.length() && input.charAt(i) != '"') {
                    if (input.charAt(i) == '\\' && i + 1 < input.length()) {
                        sb.append(input.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    sb.append(input.charAt(i++));
                }
                i++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(i) == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i != input.length() && input.charAt(i) != '\'')
                    sb.append(input.charAt(i++));
                i++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(i) == '\\') {
                dq.offerLast(String.valueOf(input.charAt(i + 1)));
                i += 2;
            } else if (input.charAt(i) == ' ') {
                if (!(dq.size() > 0 && " ".equals(dq.peekLast())))
                    dq.offerLast(" ");
                i++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (i < input.length() && input.charAt(i) != ' ' && input.charAt(i) != '\'' && input.charAt(i) != '"'
                        && input.charAt(i) != '\\') {
                    sb.append(input.charAt(i++));
                }
                dq.offerLast(sb.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        while (!dq.isEmpty())
            sb.append(dq.pollFirst());
        return sb.toString();
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>(Arrays.asList(path.split("/")));
        if (!dq.isEmpty())
            dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                if (!dq.isEmpty())
                    dq.pollLast();
                ind += 3;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != '/')
                    sb.append(input.charAt(ind++));
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder();
        while (!dq.isEmpty())
            sb.append('/').append(dq.pollFirst());
        System.setProperty("user.dir", sb.length() == 0 ? "/" : sb.toString());
    }

    public static String exists(String input) {
        Path p = Path.of(input);
        File f = p.toFile();
        return (f.exists() && f.isDirectory()) ? f.getAbsolutePath() : null;
    }

    static boolean checkExternal(String input) {
        List<String> toks = tokenizeArgs(input);
        if (toks.isEmpty())
            return false;
        String cmd = toks.get(0);
        return PathUtil.isOnPath(cmd);
    }
}
