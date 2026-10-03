#!/usr/bin/env bun
/**
 * THE LEDGER CLI — the only channel to campaign state.
 *
 *   bun dev/campaigns/ledger.ts <ledger.toml> <command> [args]
 *
 * Raw edits to the ledger are blocked by .claude/hooks/modules/02-ledger-channel.ts, because a
 * raw edit skips the lock, skips the validate-and-rollback, and risks stripping the `#` comments
 * that carry every decision and resume pointer.
 *
 * The ledger path is the first argument, matching the fleet CLI's shape, so one binary serves any
 * number of campaigns.
 */

import {
  findBlock,
  headerLines,
  isItemStatus,
  ITEM_STATUSES,
  LedgerError,
  locateItems,
  mutate,
  notesOf,
  parseOrThrow,
  readLines,
  toml,
  today,
  type ItemBlock,
  type ItemStatus,
} from "./ledger-core.ts";

const USAGE = `usage: bun dev/campaigns/ledger.ts <ledger.toml> <command> [args]

read
  list [--status S] [--phase P]     compact table of items
  get <ID>                          one item with its notes (~15 lines, not the whole file)
  next                              the next actionable item
  laws                              the law sheet from the ledger header
  packet <ID>                       a self-contained dispatch brief with the four-step finish

write
  add --id I --phase P --title T [--files a,b] [--verify V] [--status S]
  set-status <ID> <status>          ${ITEM_STATUSES.join(" | ")}
  note <ID> "text"                  append a dated note (never rewrites history)
  add-law "text"                    append a law to the header
  amend-header <old> <new>          replace a line in the header
  amend <ID> [--title T] [--verify V] [--files a,b]
                                    rewrite dispatch fields (old values auto-noted)

check
  validate                          parse the ledger and report item counts
  selftest                          exercise the CLI against a temporary ledger`;

// ── argument helpers ──────────────────────────────────────────────────────────────────────────

function flag(argv: readonly string[], name: string): string | null {
  const index = argv.indexOf(`--${name}`);
  if (index === -1) return null;
  return argv[index + 1] ?? null;
}

function required(argv: readonly string[], name: string): string {
  const value = flag(argv, name);
  if (value === null) throw new LedgerError(`--${name} is required`);
  return value;
}

function positional(argv: readonly string[], index: number, what: string): string {
  const value = argv[index];
  if (value === undefined) throw new LedgerError(`expected ${what}`);
  return value;
}

// ── read commands ─────────────────────────────────────────────────────────────────────────────

const STATUS_MARK: Record<ItemStatus, string> = {
  todo: "·",
  in_flight: "▸",
  blocked: "■",
  done: "○",
  verified: "●",
};

function renderList(blocks: readonly ItemBlock[], status: string | null, phase: string | null): string {
  const rows = blocks
    .map((block) => block.item)
    .filter((item) => (status === null || item.status === status))
    .filter((item) => (phase === null || item.phase === phase));

  if (rows.length === 0) return "no matching items";

  const widest = Math.max(...rows.map((item) => item.id.length));
  const lines = rows.map((item) => {
    const claim = item.claimedBy === undefined ? "" : `  @${item.claimedBy}`;
    return `${STATUS_MARK[item.status]} ${item.id.padEnd(widest)}  ${item.status.padEnd(9)}  ${item.phase.padEnd(10)}  ${item.title}${claim}`;
  });

  const tally = ITEM_STATUSES.map((candidate) => {
    const count = blocks.filter((block) => block.item.status === candidate).length;
    return count === 0 ? null : `${candidate} ${count}`;
  }).filter((entry) => entry !== null);

  return `${lines.join("\n")}\n\n${rows.length} shown · ${tally.join(" · ")}`;
}

function renderItem(lines: readonly string[], block: ItemBlock): string {
  const { item } = block;
  const notes = notesOf(lines, block);
  const body = [
    `${STATUS_MARK[item.status]} ${item.id}  [${item.status}]  phase=${item.phase}`,
    ``,
    `  ${item.title}`,
    ``,
    `  files  : ${item.files.length === 0 ? "(none declared)" : item.files.join(", ")}`,
    `  verify : ${item.verify === "" ? "(none declared)" : item.verify}`,
  ];
  if (item.claimedBy !== undefined) {
    body.push(`  claim  : ${item.claimedBy} since ${item.claimedAt ?? "unknown"}`);
  }
  if (notes.length > 0) {
    body.push(``, `  notes (append-only — the construction diary):`);
    for (const note of notes) body.push(`    ${note}`);
  }
  return body.join("\n");
}

/**
 * The next actionable item: an in-flight one if any is open (finish before starting), otherwise
 * the first todo. Blocked items are never "next" — a blocked item needs a ruling, not a builder.
 */
function pickNext(blocks: readonly ItemBlock[]): ItemBlock | null {
  return (
    blocks.find((block) => block.item.status === "in_flight") ??
    blocks.find((block) => block.item.status === "todo") ??
    null
  );
}

/** A law is a header line starting `# LAW`: `# LAW:`, `# LAW [date]:`, `# LAW LP-6 …`. */
function lawLines(lines: readonly string[]): string[] {
  return headerLines(lines).filter((line) => /^#\s*LAW\b/.test(line));
}

/**
 * A dispatch packet. Self-contained by construction (concept #945 §5): laws, the item text, its
 * files, the verify line, and the four-step finish. A packet that makes the reader open the ledger
 * to understand it has already failed — the point is that a fresh context can act.
 */
function renderPacket(ledgerPath: string, lines: readonly string[], block: ItemBlock): string {
  const { item } = block;
  const laws = lawLines(lines).map((line) => `  ${line.replace(/^\s*#\s*/, "")}`);
  const cli = `bun dev/campaigns/ledger.ts ${ledgerPath}`;

  return [
    `ITEM ${item.id} — ${item.phase}`,
    ``,
    item.title,
    ``,
    `FILES (take a file lock only on a file another open row also names):`,
    item.files.length === 0
      ? `  (none declared)`
      : item.files.map((file) => `  ${file}`).join("\n"),
    ``,
    `VERIFY (scoped tests for what the row touched, plus the typecheck, until green):`,
    `  ${item.verify === "" ? "(none declared)" : item.verify}`,
    ``,
    `LAWS IN FORCE:`,
    laws.length === 0 ? `  (none in header)` : laws.join("\n"),
    ``,
    `FINISH (these four steps and nothing else):`,
    `  1. ${cli} set-status ${item.id} in_flight`,
    `  2. Edit the row's files.`,
    `  3. Run the verify line until green.`,
    `  4. ${cli} note ${item.id} "<command> exit=<code> tests=<count>"`,
    `     ${cli} set-status ${item.id} done`,
    `     Then ONE commit of the row's files plus the ledger, by explicit path, message starting`,
    `     with the row id:  git commit -m "${item.id}: <what changed>" -- <files> ${ledgerPath}`,
    `     (a new file: git add <file> && git commit ... in one command).`,
    `  The orchestrator sets verified once the milestone's PR lands green.`,
    ``,
    `PREMISE CHECK:`,
    `  If anything in this packet contradicts the repo, REPORT it — do not obey it. A wrong`,
    `  premise from the orchestrator is still a wrong premise.`,
  ].join("\n");
}

// ── write commands ────────────────────────────────────────────────────────────────────────────

function withStatus(lines: readonly string[], block: ItemBlock, status: ItemStatus): string[] {
  const next = [...lines];
  for (let index = block.start; index < block.end; index += 1) {
    if (/^\s*status\s*=/.test(next[index] ?? "")) {
      next[index] = `status = ${toml(status)}`;
      return next;
    }
  }
  next.splice(block.end, 0, `status = ${toml(status)}`);
  return next;
}

function withNote(lines: readonly string[], block: ItemBlock, text: string): string[] {
  const next = [...lines];
  next.splice(block.end, 0, `# ${today()} ${text}`);
  return next;
}

function withField(lines: readonly string[], block: ItemBlock, key: string, value: string): string[] {
  const next = [...lines];
  for (let index = block.start; index < block.end; index += 1) {
    if (new RegExp(`^\\s*${key}\\s*=`).test(next[index] ?? "")) {
      next[index] = `${key} = ${toml(value)}`;
      return next;
    }
  }
  next.splice(block.end, 0, `${key} = ${toml(value)}`);
  return next;
}

// ── dispatch ──────────────────────────────────────────────────────────────────────────────────

async function main(): Promise<number> {
  const argv = Bun.argv.slice(2);
  const ledgerPath = argv[0];
  const command = argv[1];

  if (ledgerPath === undefined || command === undefined || command === "help") {
    console.log(USAGE);
    return ledgerPath === undefined ? 1 : 0;
  }

  if (command === "selftest") return await selftest();

  const rest = argv.slice(2);

  /**
   * `verified` IS THE ORCHESTRATOR'S WORD.
   *
   *   done      a builder finished the row: note written, status set, one commit
   *   verified  the orchestrator sets it once the milestone's PR lands green
   *
   * Same honest limit as everywhere else: on a NOPASSWD host a builder that wants to set this can.
   * What the check buys is that doing so becomes deliberate and self-incriminating rather than the
   * default path.
   */
  const ORCHESTRATOR_ENV = "LEDGER_ORCHESTRATOR";
  const claimsVerified =
    (command === "set-status" && rest[1] === "verified") ||
    (command === "add" && flag(rest, "status") === "verified");

  if (claimsVerified && (process.env[ORCHESTRATOR_ENV] ?? "") !== "1") {
    throw new LedgerError(
      `"verified" is the orchestrator's word, not a builder's.\n\n` +
        `  done      a builder finished the row: note written, status set, one commit\n` +
        `  verified  the orchestrator sets it once the milestone's PR lands green\n\n` +
        `Set it to "done"; the orchestrator verifies. If you ARE the orchestrator,\n` +
        `re-run with ${ORCHESTRATOR_ENV}=1 set inline.\n\n` +
        `Stated plainly: on a NOPASSWD host a builder that wants to set this can. What the check\n` +
        `buys is that doing so is deliberate rather than the default path.`,
    );
  }

  const lines = await readLines(ledgerPath);
  const blocks = locateItems(lines);

  switch (command) {
    case "list":
      console.log(renderList(blocks, flag(rest, "status"), flag(rest, "phase")));
      return 0;

    case "get":
      console.log(renderItem(lines, findBlock(blocks, positional(rest, 0, "an item id"))));
      return 0;

    case "next": {
      const chosen = pickNext(blocks);
      console.log(chosen === null ? "queue empty" : renderItem(lines, chosen));
      return 0;
    }

    case "laws": {
      const laws = lawLines(lines);
      console.log(laws.length === 0 ? "no laws in header" : laws.join("\n"));
      return 0;
    }

    case "packet":
      console.log(renderPacket(ledgerPath, lines, findBlock(blocks, positional(rest, 0, "an item id"))));
      return 0;

    case "validate": {
      const parsed = parseOrThrow(lines.join("\n"), ledgerPath) as { items?: unknown[] };
      const parsedCount = Array.isArray(parsed.items) ? parsed.items.length : 0;
      if (parsedCount !== blocks.length) {
        throw new LedgerError(
          `line scan found ${blocks.length} items but the parser found ${parsedCount} — ` +
            `the scanner and the parser disagree, which means one of them is wrong about this file`,
        );
      }
      console.log(`${ledgerPath}: valid · ${blocks.length} items`);
      return 0;
    }

    case "set-status": {
      const id = positional(rest, 0, "an item id");
      const status = positional(rest, 1, `a status (${ITEM_STATUSES.join("|")})`);
      if (!isItemStatus(status)) throw new LedgerError(`"${status}" is not a status`);
      await mutate(ledgerPath, (current) =>
        withStatus(current, findBlock(locateItems(current), id), status),
      );
      console.log(`${id} → ${status}`);
      return 0;
    }

    case "note": {
      const id = positional(rest, 0, "an item id");
      const text = positional(rest, 1, "note text");
      await mutate(ledgerPath, (current) =>
        withNote(current, findBlock(locateItems(current), id), text),
      );
      console.log(`${id}: note appended`);
      return 0;
    }

    case "add": {
      const id = required(rest, "id");
      if (blocks.some((block) => block.item.id === id)) {
        throw new LedgerError(`item "${id}" already exists`);
      }
      const files = (flag(rest, "files") ?? "")
        .split(",")
        .map((piece) => piece.trim())
        .filter((piece) => piece !== "");
      const status = flag(rest, "status") ?? "todo";
      if (!isItemStatus(status)) throw new LedgerError(`"${status}" is not a status`);

      await mutate(ledgerPath, (current) => {
        const trimmed = [...current];
        while (trimmed.length > 0 && (trimmed.at(-1) ?? "").trim() === "") trimmed.pop();
        return [
          ...trimmed,
          ``,
          `[[items]]`,
          `id = ${toml(id)}`,
          `phase = ${toml(required(rest, "phase"))}`,
          `title = ${toml(required(rest, "title"))}`,
          `files = [${files.map((file) => toml(file)).join(", ")}]`,
          `status = ${toml(status)}`,
          `verify = ${toml(flag(rest, "verify") ?? "")}`,
          ``,
        ];
      });
      console.log(`added ${id}`);
      return 0;
    }

    case "amend": {
      const id = positional(rest, 0, "an item id");
      const title = flag(rest, "title");
      const verify = flag(rest, "verify");
      const filesCsv = flag(rest, "files");
      if (title === null && verify === null && filesCsv === null) {
        throw new LedgerError("amend: provide at least one of --title, --verify, --files");
      }
      await mutate(ledgerPath, (current) => {
        let next = [...current];
        let block = findBlock(locateItems(next), id);
        const audit: string[] = [];
        if (title !== null) {
          audit.push(`title was ${toml(block.item.title)}`);
          next = withField(next, block, "title", title);
          block = findBlock(locateItems(next), id);
        }
        if (verify !== null) {
          audit.push(`verify was ${toml(block.item.verify)}`);
          next = withField(next, block, "verify", verify);
          block = findBlock(locateItems(next), id);
        }
        if (filesCsv !== null) {
          const files = filesCsv
            .split(",")
            .map((piece) => piece.trim())
            .filter((piece) => piece !== "");
          audit.push(`files were [${block.item.files.map((file) => toml(file)).join(", ")}]`);
          let replaced = false;
          for (let index = block.start; index < block.end; index += 1) {
            if (/^\s*files\s*=/.test(next[index] ?? "")) {
              next[index] = `files = [${files.map((file) => toml(file)).join(", ")}]`;
              replaced = true;
              break;
            }
          }
          if (!replaced) throw new LedgerError(`item "${id}" has no files line`);
          block = findBlock(locateItems(next), id);
        }
        // The old values are the audit trail: an amend that leaves no trace of what it replaced
        // is a rewrite of history, which is exactly what this CLI exists to prevent.
        return withNote(next, block, `amend: ${audit.join("; ")}`);
      });
      console.log(`${id}: amended — old values preserved as a dated note`);
      return 0;
    }

    case "add-law": {
      const text = positional(rest, 0, "law text");
      await mutate(ledgerPath, (current) => {
        const header = headerLines(current);
        let insertAt = header.length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        const next = [...current];
        next.splice(insertAt, 0, `# LAW: ${text}`);
        return next;
      });
      console.log("law appended");
      return 0;
    }

    case "amend-header": {
      const old = positional(rest, 0, "the existing header text");
      const replacement = positional(rest, 1, "the replacement text");
      await mutate(ledgerPath, (current) => {
        const headerLength = headerLines(current).length;
        const index = current.findIndex((line, at) => at < headerLength && line.includes(old));
        if (index === -1) throw new LedgerError(`no header line contains "${old}"`);
        const next = [...current];
        next[index] = (next[index] ?? "").replace(old, replacement);
        return next;
      });
      console.log("header amended");
      return 0;
    }

    default:
      console.error(`unknown command "${command}"\n\n${USAGE}`);
      return 1;
  }
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────────

/**
 * Runs the CLI against a throwaway ledger. Required at every vendoring: a ledger CLI that has
 * never been watched preserve a comment is a ledger CLI that will one day eat the memory.
 */
async function selftest(): Promise<number> {
  const path = `${process.env["TMPDIR"] ?? "/tmp"}/eli-ledger-selftest-${process.pid}.toml`;
  let failures = 0;
  let checks = 0;

  const check = (label: string, ok: boolean, detail = ""): void => {
    checks += 1;
    if (ok) return;
    failures += 1;
    console.error(`  FAIL  ${label}${detail === "" ? "" : `\n        ${detail}`}`);
  };

  await Bun.write(
    path,
    [
      `# selftest ledger`,
      `# LAW: manifest-is-memory — an item must be resumable from the ledger alone`,
      `# LAW [2026-10-03]: dated-law-form-reads`,
      `# LAW LP-6 (2026-07-03): id-law-form-reads`,
      `# a header line that mentions a LAW mid-sentence is not-a-law`,
      ``,
      `[[items]]`,
      `id = "H1"`,
      `phase = "harness"`,
      `title = "first item"`,
      `files = ["dev/a.ts"]`,
      `status = "todo"`,
      `verify = "bun run gate"`,
      `# 2026-07-26 a pre-existing note that must survive every write`,
      `# require:ready:unit`,
      ``,
      `[[items]]`,
      `id = "H2"`,
      `phase = "harness"`,
      `title = "second item"`,
      `files = []`,
      `status = "verified"`,
      `verify = ""`,
      `claimed_by = "old-seat"`,
      `claimed_at = "2026-07-01T00:00:00Z"`,
      ``,
    ].join("\n"),
  );

  // Every spawn runs as a builder unless asked otherwise, so an orchestrator running the selftest
  // with LEDGER_ORCHESTRATOR=1 exported still exercises the builder's view.
  const runAs = async (orchestrator: boolean, ...args: string[]): Promise<string> => {
    const proc = Bun.spawn(["bun", import.meta.path, path, ...args], {
      stdout: "pipe",
      stderr: "pipe",
      env: { ...process.env, LEDGER_ORCHESTRATOR: orchestrator ? "1" : "" },
    });
    const out = await new Response(proc.stdout).text();
    const err = await new Response(proc.stderr).text();
    await proc.exited;
    return out + err;
  };
  const run = (...args: string[]): Promise<string> => runAs(false, ...args);

  console.log("ledger selftest");

  check("list shows both items", (await run("list")).includes("H1") && (await run("list")).includes("H2"));
  check("list --status filters", !(await run("list", "--status", "todo")).includes("H2"));
  check("get returns the item", (await run("get", "H1")).includes("first item"));
  check("get returns the item, not the file", !(await run("get", "H1")).includes("manifest-is-memory"));
  check("get surfaces existing notes", (await run("get", "H1")).includes("must survive"));
  check("next prefers an open item", (await run("next")).includes("H1"));

  const laws = await run("laws");
  check("laws reads `# LAW:`", laws.includes("manifest-is-memory"));
  check("laws reads `# LAW [date]:`", laws.includes("dated-law-form-reads"));
  check("laws reads `# LAW <id>`", laws.includes("id-law-form-reads"));
  check("laws skips a header line that only mentions LAW", !laws.includes("not-a-law"));

  const packet = await run("packet", "H1");
  check("packet carries the laws", packet.includes("manifest-is-memory") && packet.includes("dated-law-form-reads"));
  check("packet names the row's files", packet.includes("dev/a.ts"));
  check("packet carries the four-step finish", packet.includes("ONE commit") && packet.includes("set-status H1 done"));
  check("packet says when verified is set", packet.includes("milestone's PR lands green"));
  check("packet has no commit ban or fence", !packet.includes("DO NOT COMMIT") && !packet.includes("FENCE"));

  check("an old claim still reads", (await run("get", "H2")).includes("old-seat"));
  check("claim is no longer a verb", (await run("claim", "H1", "builder-1")).includes("unknown command"));
  check("release-stale is no longer a verb", (await run("release-stale")).includes("unknown command"));

  await run("set-status", "H1", "in_flight");
  await run("note", "H1", "a note added by the selftest");

  const afterWrites = await Bun.file(path).text();

  // THE LOAD-BEARING ASSERTION. A serializer round-trip would have silently eaten both of these,
  // the file would still parse, every other check here would still pass, and the campaign's
  // memory would be gone. This is the check the whole line-surgical design exists to satisfy.
  check("header comments survive writes", afterWrites.includes("# LAW: manifest-is-memory"));
  check("pre-existing item notes survive writes", afterWrites.includes("must survive every write"));
  check("the new note landed", afterWrites.includes("a note added by the selftest"));
  check("the note is dated", new RegExp(`# ${today()} a note added`).test(afterWrites));
  check("set-status took effect", (await run("get", "H1")).includes("[in_flight]"));

  // A row finishes with a note, set-status done and one commit: no receipt, proof or review.
  // H1 carries a legacy `# require:` note, which the earned-row plane used to enforce.
  check("set-status done needs no receipt", (await run("set-status", "H1", "done")).includes("H1 → done"));
  check("done took effect", (await run("get", "H1")).includes("[done]"));

  // `verified` stays the orchestrator's word.
  const beforeVerified = await Bun.file(path).text();
  check("a builder cannot set verified", (await run("set-status", "H1", "verified")).includes("orchestrator's word"));
  check("the refused verified wrote nothing", (await Bun.file(path).text()) === beforeVerified);
  check("the orchestrator can set verified", (await runAs(true, "set-status", "H1", "verified")).includes("H1 → verified"));

  await run("add", "--id", "H3", "--phase", "harness", "--title", "third", "--verify", "bun run gate");
  check("add created the item", (await run("get", "H3")).includes("third"));
  check("duplicate ids are refused", (await run("add", "--id", "H3", "--phase", "p", "--title", "t")).includes("already exists"));

  // The law and amend channels are plain ledger writes: no grant, and the old value is kept.
  check("add-law appends to the header", (await run("add-law", "silence-is-a-system-bug")).includes("law appended"));
  check("the new law reads", (await run("laws")).includes("silence-is-a-system-bug"));
  check("add-law kept the existing laws", (await run("laws")).includes("dated-law-form-reads"));
  check("amend-header replaces a header line", (await run("amend-header", "id-law-form-reads", "id-law-amended")).includes("header amended"));
  check("the amended law reads", (await run("laws")).includes("id-law-amended"));

  check("amend replaces a field", (await run("amend", "H3", "--verify", "a brand new verify")).includes("amended"));
  const amended = await run("get", "H3");
  check("amend took effect", amended.includes("verify : a brand new verify"));
  check("amend preserved the old value as a dated note", amended.includes("amend: verify was"));
  check("amend with no field flags is refused", (await run("amend", "H3")).includes("at least one of"));

  // A FAILED MUTATION rolls back: `note NOPE` throws inside mutate's transform, after the lock is
  // taken, which is the path that has to leave the file byte-identical and release the lock.
  const beforeFailed = await Bun.file(path).text();
  check("a failed mutation is reported", (await run("note", "NOPE", "no such item")).includes("no item with id"));
  check("a failed mutation leaves the file byte-identical", (await Bun.file(path).text()) === beforeFailed);
  check("a failed mutation releases its lock", !(await Bun.file(`${path}.lock`).exists()));

  check("validate passes", (await run("validate")).includes("valid"));
  check("unknown ids are refused", (await run("get", "NOPE")).includes("no item with id"));
  check("invalid statuses are refused", (await run("set-status", "H1", "sideways")).includes("not a status"));

  await Bun.file(path).delete().catch(() => {});
  await Bun.file(`${path}.lock`).delete().catch(() => {});

  console.log(`${checks - failures}/${checks} checks passed`);
  return failures > 0 ? 1 : 0;
}

try {
  process.exit(await main());
} catch (error) {
  if (error instanceof LedgerError) {
    console.error(`ledger: ${error.message}`);
    process.exit(1);
  }
  throw error;
}
