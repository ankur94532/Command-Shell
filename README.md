Minimal Shell (Java)

Tiny, readable shell with interactive line editing, tab-completion, pipelines, redirection, and a handful of built-ins. This README documents each extension I implemented, ordered hardest → easiest, and notes the core data structures (incl. a Trie for completion).


---

Feature Index (hard → easy)

1. Interactive line editing + Tab completion (Trie) + History navigation


2. Pipelines (cmd1 | cmd2 | ...) for built-ins & externals


3. Redirection: >, >>, and 2>


4. Robust parsing: quoting, escaping & pipeline splitting


5. History builtin (with file load/save/append)


6. Built-ins: cd, type, pwd, echo


7. External command discovery & execution


8. Extras & behavior notes


9. Limits & next steps




---

1) Interactive line editing + Tab completion (Trie) + History navigation

What I added

Raw TTY input for key-by-key edits (using stty -echo -icanon min 1) and a simple editor loop.

Arrow keys:

Up / Down: browse command history.

Backspace: delete last char.

Enter: submit.


Tab completion powered by a Trie:

I scan $PATH for executables and insert names into a fixed-fanout Trie (128 children per node).

Single match → auto-complete (and add a trailing space if the token is a complete command).

Ambiguous: first Tab rings a bell, second Tab prints all matches in columns and redraws the prompt.

Special small conveniences for echo/exit prefixes (e, ec, ech, ex, exi).


History navigation is inline (live redraw), not just after pressing Enter.


Data structures used

Trie / TrieNode: for fast prefix completion (insert, unique search, checkComplete).

In-memory history (ArrayList<String>) with a cursor for Up/Down.



---

2) Pipelines (cmd1 | cmd2 | ...) for built-ins & externals

What I added

Full pipeline support that mixes built-ins and external processes.

Internal Proc interface with two implementations:

BuiltinProc: runs a built-in in a worker thread, wiring stdin/stdout/stderr via pipes.

ExternalProc: wraps a ProcessBuilder process.


A pump layer connects stdout → next stdin for each segment, and forwards the last segment’s stdout to the terminal. All stderr streams are mirrored to the terminal.


Notes

Built-ins available inside pipelines: echo, type, cd, pwd.

history runs in the main loop (not piped as a segment).



---

3) Redirection: >, >>, and 2>

What I added

>: truncate/create destination file.

>>: append to destination file.

2>: redirect stderr to a file (supported for external commands I handle through ProcessBuilder).

Works with quoted or spaced paths; creates parent directories as needed.


Command coverage

echo: I render the text (honoring quotes/escapes) and write to file (append or truncate).

ls / cat: executed via ProcessBuilder with output/error redirection:

STDOUT → file (or terminal), STDERR → terminal (or file if 2> is used).


For bare cat > file (no sources), I create/truncate the file (empty).



---

4) Robust parsing: quoting, escaping & pipeline splitting

What I added

Argument tokenization that understands:

Single quotes (literal), double quotes (supports backslash escapes within), and backslash escaping outside quotes.

Whitespace delimiting outside quotes only.


Pipeline splitter that respects quotes/escapes so | inside quotes doesn’t split.

Helpers for unquoting redirection targets and extracting first tokens.



---

5) history builtin (with file load/save/append)

What I added

history prints the entire in-memory history (numbered).

history N prints the last N entries.

File integration:

history -r <file>: load from file.

history -w <file>: write all history to file.

history -a <file>: append only new entries since the last append (tracked per file).


On exit, if HISTFILE is set, I persist history to that file.



---

6) Built-ins: cd, type, pwd, echo

cd

cd with no args → $HOME (or user.home).

cd ~ → home.

Relative navigation with . and folded .. segments (I maintain my own working dir).

Clear error if the target doesn’t exist.


type

Reports if a name is a shell builtin or shows its absolute path on $PATH; otherwise “not found”.


pwd

Prints the shell’s current working directory (kept in sync with cd).


echo

Prints its arguments exactly as parsed (quotes/escapes honored).



---

7) External command discovery & execution

What I added

$PATH scanning to detect executables; direct paths (containing a separator) are executed as-is.

For non-pipeline commands, I run the process with the shell’s current working directory and stream its STDOUT to the terminal.



---

8) Extras & behavior notes

Prompt: $  (simple, persistent).

ANSI redraw: clears & redraws the current line when editing/history navigation happens.

Quoted filenames for cat are supported (single or double quotes).

CWD semantics: both built-ins and spawned processes honor the shell’s current working directory.


Core utilities

Path/Files for robust filesystem and directory creation.

ProcessBuilder for external execution and I/O redirection.

Small I/O helpers for safe close and efficient pumps.



---

9) Limits & next steps

No globbing (*, ?) or environment variable expansion ($HOME, ${VAR}).

No job control/backgrounding (&), no 2>&1 merging, no here-docs (<<).

Limited in-pipeline built-ins (currently echo, type, cd, pwd).

Command substitution ($(...)), aliases, and inline editing beyond backspace are not implemented.



---

TL;DR

This shell aims to be small but useful: edit-as-you-type, Trie-backed Tab completion, history with persistence, pipelines, and real redirection — all while keeping the code easy to read and extend.

