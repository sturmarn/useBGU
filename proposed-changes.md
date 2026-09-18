# Proposed Fixes, Extensions, and Changes

A backlog of proposed future work — things worth doing but not yet
started, as opposed to `known-issues.org` (org-mode, tracks bugs actually
found and either fixed or deliberately left open while building the
`examples/` corpus). Nothing in this file has been implemented. Each
entry is self-contained enough to pick up cold in a future session.

**Format per entry:** a short title, a `Tags:` line for topical grouping
(so entries about the same subsystem can be found together as the file
grows), a description of the finding, a proposed fix, and — where
checked — the actual blast radius (who calls the affected code today),
rather than a guess.

---

## `MMultiLevelModel.getParentModel(String)` infers a model's parent by declaration order, not by an actual mediator

**Tags:** levels

**Status:** FIXED. Implemented and verified — full `use-core` suite
413/413 (409 baseline + 4 new regression tests, no failures elsewhere).

**Location:** `use-core/src/main/java/org/tzi/use/uml/mm/MMultiLevelModel.java:118-131`, live caller at `use-core/src/main/java/org/tzi/use/api/UseMLMApi.java:108`.

### The finding

```java
public MModel getParentModel(String modelName) {
    if (!fModels.containsKey(modelName)){
        return null;
    }

    MModel prevModel = null;
    for (MModel model : fModelsList){
        if (model.name().equals(modelName)){
            return prevModel;
        }
        prevModel = model;
    }
    return prevModel;
}
```

This walks `fModelsList` (models in declaration order) and returns
whichever model happens to have been declared *immediately before* the
named one — it has no reference to any actual mediator, clabject, or
parent/child relationship at all. It's the same "declaration order !=
hierarchy order" antipattern that `MMultiLevelModel.checkState()`'s own
comment documents as already found and fixed elsewhere in this exact
class:

```java
// Iterate mediators and use each one's own, explicitly-recorded
// parent model (mediator.getParentModel()) rather than pairing
// models by walking this.models() in sequence -- that collection
// is inherited from MMultiModel's TreeMap<String,MModel> and so
// iterates *alphabetically* by model name, not in level-hierarchy
// order, which silently paired each level with the wrong parent
// whenever level names didn't happen to sort in hierarchy order.
```

`getParentModel(String)` is a *second*, separate instance of the same
class of bug — never touched by that earlier fix, and it uses
declaration order (not even the alphabetical order `checkState()` was
fixed away from), which is just as unreliable: nothing in the grammar
requires — or even suggests — that a model's parent be declared
immediately before it. This was directly confirmed empirically in an
earlier investigation (see `known-issues.org`'s "Addendum: CatMLM
level-order..."): a 3-level spec where the bottom level's clabject
instantiates the top level's class directly, skipping the middle level
entirely, compiles clean and reports `WellDefined` — parent/child
relationships are resolved purely by the name each mediator explicitly
declares (`mediator ID1 < ID2`), never by position.

**This isn't dead code.** `UseMLMApi.createMediator(String mediatorName, String relatedModel)`
— the public, programmatic API for building an MLM model from Java —
calls it directly:

```java
public MMediator createMediator(String mediatorName, String relatedModel) throws Exception {
    MModel currentModel = mMultiLevelModel.getModel(relatedModel);
    ...
    MModel parentModel = mMultiLevelModel.getParentModel(relatedModel);
    ...
    mediator.setParentModel(parentModel);
    return mediator;
}
```

So a caller building a multi-level model programmatically, whose models
happen not to be added to the `MMultiLevelModel` in hierarchy order,
would get silently wired up with the *wrong* parent model — no
exception, no warning, just an incorrect mediator.

**Not yet confirmed as an active failure in this codebase** — the
existing callers found (`TestMLMUtil.java`, ~20 call sites, e.g.
`createMediator("AB", "AB")` then `createMediator("CD", "CD")`) all
appear to add their models in hierarchy order already, so the bug is
latent rather than observed. That's worth stating honestly rather than
overclaiming a live failure — but it means the *design* of
`createMediator`'s signature is the actual problem, not just this one
method's implementation: it structurally cannot express "this model's
parent is that one" the way the grammar always can (`mediator ID1 <
ID2`, an explicit name, any declaration order, confirmed order-
independent), because it never asks the caller for the parent at all —
it tries to *infer* it from a single model name.

### Fix implemented

`MMultiLevelModel.getParentModel(String)` now looks the model's own
mediator up directly (`fMediators.get(modelName)`) and returns *that*
mediator's parent, instead of walking `fModelsList` positionally:

```java
public MModel getParentModel(String modelName) {
    if (!fModels.containsKey(modelName)){
        return null;
    }
    MMediator mediator = fMediators.get(modelName);
    if (mediator == null) {
        return null;
    }
    return mediator.getParentModel();
}
```

The one live caller, `UseMLMApi.createMediator`, couldn't just switch to
this same lookup — it's called to *create* a model's mediator, so no
mediator exists yet for it to consult at that point. Resolved by giving
it an explicit parent parameter instead of inferring one, mirroring the
grammar it builds the same structure as (`mediator ID1 < ID2`):

```java
// unchanged signature, now means "no parent" (a root level) instead of
// "guess positionally":
public MMediator createMediator(String mediatorName, String relatedModel) throws Exception

// new overload, for the non-root case:
public MMediator createMediator(String mediatorName, String relatedModel, String parentModelName) throws Exception
```

Kept the two-argument overload rather than removing it (delegating to
the three-argument one with `null`) — its meaning changes from "guess a
parent positionally" to "no parent," which is a real behavior change,
but not a removal, so nothing calling it needs to change at all; only
callers that actually needed a parent (and were relying on the guess
happening to be right) need to migrate to the new overload.

**Blast radius, checked exactly, not estimated**: `TestMLMUtil.java` was
the only caller anywhere in `use-core`/`use-gui` (confirmed via grep).
Traced every one of its ~15 fixture methods against their own
subsequent `createClabject`/`createAssoclink` calls (which reveal the
*intended* parent, since `createClabject` resolves its parent-side class
via `mediator.getParentModel().getClass(parentName)` — so a wrong or
missing parent there doesn't just mis-set a field, it breaks clabject
creation outright) to work out which `createMediator` calls actually
needed a parent argument added, rather than adding one everywhere by
reflex: 4 calls migrated to the three-argument form with an explicit
parent (one `Mediator2`/`personCompany1` pair, `"CD"`→`"AB"` ×10 via one
`replace_all` since every occurrence needed the identical fix, and one
`"EF"`→`"CD"` for the three-level fixture); every `"AB"`/`"Mediator1"`
call stayed on the two-argument (root) form, since nothing in any
fixture ever reads their parent.

### Verification

- New regression tests in `MLMCreationTest.java`:
  - `testGetParentModelUsesMediatorNotDeclarationOrder` — the actual
    regression test: compiles a plain-MLM-USE spec with the *child*
    model ("Child") declared textually before its parent ("Parent"),
    with a mediator explicitly naming "Parent" as Child's parent. This
    is exactly the shape that fooled the old implementation (it would
    have returned `null` for Child, and `Child` itself for Parent, both
    wrong); asserts the correct answers for both.
  - `testGetParentModelReturnsNullForModelWithNoMediator` — a model with
    no mediator at all still returns `null`, not an exception.
  - `testGetParentModelReturnsNullForUnknownModel` — preserves existing
    behavior for a name that isn't a model at all.
  - `testCreateMediatorWithExplicitParentIsReflectedByGetParentModel` —
    ties both halves of the fix together: the new three-argument
    `createMediator` overload wires the mediator up correctly, and
    `getParentModel(String)` surfaces that same correct answer
    afterward, using the existing `createMLM1()` fixture (now migrated
    to the explicit-parent form) rather than a fresh one.
- Full `use-core` suite: 413/413 (409 -> 413, the four tests above; no
  regressions in any of `TestMLMUtil.java`'s other consumers, e.g.
  `MLMCreationTest`'s own pre-existing `checkState()`/attribute-renaming
  tests, which depend on the migrated fixtures compiling and behaving
  identically to before).

---

## PlantUML diagram generator draws the child above the parent — for both generalizations and instanceOf — needs reversing

**Tags:** diagrams, latex, plantuml, levels

**Status:** proposed, not started. Diagnosed and confirmed empirically
(rendered an actual example diagram and looked at it), fix not yet
attempted or verified.

**Location:** `use-core/src/main/java/org/tzi/use/tools/catuselatex/PlantUmlDiagramGenerator.java:47-59`.

### The finding

`generate()` builds the class-diagram relation lines like this:

```java
DirectedGraph<MClassifier, MGeneralization> genGraph = mlm.generalizationGraph();
Iterator<MGeneralization> edges = genGraph.edgeIterator();
while (edges.hasNext()) {
    MGeneralization edge = edges.next();
    String child = alias(edge.child().name());
    String parent = alias(edge.parent().name());
    if (edge instanceof MClabject) {
        relations.add(child + " ..> " + parent + " : <<instanceOf>>");
    } else {
        relations.add(child + " --|> " + parent);
    }
}
```

i.e. every relation, whether an ordinary same-level generalization
(`--|>`) or a clabject `instanceOf` edge (`..>`), is written with the
**child first** (the arrow's source) and the **parent second** (the
arrow's target/arrowhead).

Rendered `examples/CatMLM/202608282120-C14_diagram.pdf` (a real, already-
generated example: a 4-level clabject chain, M1 the most concrete level
through M4 the root) to check the actual visual result rather than
reasoning about PlantUML's layout engine abstractly. Confirmed directly
from the image: `M1_S ..> M2_R` renders with S (child, in M1) *above*
R (parent, in M2); `M1_S --|> M1_Sup3` renders with S *above* Sup3
(parent). Every edge in the diagram, of both kinds, places the source
(child) above the target (parent) — the opposite of the desired "parent
on top, child/instance below."

**Interesting, worth knowing before touching this:** `orderedLevels()`
(same file, lines 78-106) already exists specifically to put "parent
levels before children" — and does so correctly at the *package* level
(confirmed from the generated `.puml` source: `package "M4"` is emitted
first, `package "M1"` last, exactly matching that function's own
depth computation). But it has **no visible effect on the final
rendered layout** — PlantUML's automatic layout engine follows the
*relation graph* (which edge points to which), not package-declaration
order, for vertical placement of the classes involved in a relation.
So the existing "parent packages first" logic is solving a problem
PlantUML doesn't actually have here; the real lever is the direction
each relation line is written in.

### Proposed fix

Swap which side is written first in both relation kinds, so the
**parent** becomes the arrow's source (and therefore, per the same
mechanism that currently puts the child on top, should end up placed
above the child):

```java
if (edge instanceof MClabject) {
    relations.add(parent + " <.. " + child + " : <<instanceOf>>");
} else {
    relations.add(parent + " <|-- " + child);
}
```

`parent <|-- child` and `parent <.. child` are PlantUML's own reversed
spellings of the identical relationships (`<|--`/`<..` mean the same
thing as `--|>`/`..>`, just written with the other class named first) —
so this is a pure textual reversal, not a semantic change to what the
diagram asserts.

**Caveat, stated honestly:** the diagnosis above (current behavior is
wrong, and *why*) is empirically confirmed by rendering and looking.
The proposed fix's exact syntax is a well-grounded hypothesis based on
that confirmed mechanism (source-above/target-below), but hasn't itself
been rendered and checked yet. Once implemented, re-render this same
C14 example (or any multi-level one) to a PNG and look at it directly —
this project's own established standard for this exact kind of
change (`pdflatex`/PlantUML succeeding proves nothing about the visual
result; only looking at the rendered image does).

### Related, out of scope for this entry

An identical `orderedLevels()` helper (same logic, same "parent levels
before children" comment, byte-for-byte identical body) exists in
**three** places total, not just this file — corrected after a fuller
grep turned up a third copy missed on first pass:
`use-core/src/main/java/org/tzi/use/uml/mm/MMPrintVisitor.java:623-646`
(drives plain-text printing order, the GUI/shell's model dump) and
`use-core/src/main/java/org/tzi/use/tools/catuselatex/FlatUseTextRenderer.java:617-640`
(drives the order levels are emitted in flattened plain-USE output).
Neither is a graphical diagram, so neither is affected by *this* fix —
but see the entry below for the duplication itself, worth fixing
independently of the vertical-layout bug this entry is about.

---

## `orderedLevels()` is implemented three times, verbatim, instead of once

**Tags:** levels, cleanup

**Status:** FIXED. Implemented and verified — full `use-core` suite
415/415 (413 baseline + 2 new tests, no failures elsewhere), and the
refactor confirmed byte-for-byte behavior-preserving (see Verification).

**Location:** all three, byte-for-byte identical:
- `use-core/src/main/java/org/tzi/use/tools/catuselatex/PlantUmlDiagramGenerator.java:83-106`
- `use-core/src/main/java/org/tzi/use/uml/mm/MMPrintVisitor.java:623-646`
- `use-core/src/main/java/org/tzi/use/tools/catuselatex/FlatUseTextRenderer.java:617-640`

### The finding

Each file has its own private static copy of the same ~24-line method
(same doc comment, same variable names, same body): build a `parentOf`
map from `mlm.mediators()`, compute each model's depth by walking that
map back to a root, then sort `mlm.models()` by depth. Three independent
call sites (`PlantUmlDiagramGenerator.generate()`,
`MMPrintVisitor.visitMLM()`, `FlatUseTextRenderer.render()`) each need
"levels, parent-first," and each got its own copy rather than a shared
one — presumably because `MMultiLevelModel` itself doesn't expose this
directly (see this file's earlier entry on `getParentModel(String)`:
`.models()` only ever gives alphabetical order, and there's no public
API for hierarchy order at all).

Three consequences of the duplication, beyond the ordinary "three things
to keep in sync" maintenance cost:

- The vertical-layout entry above only touches
  `PlantUmlDiagramGenerator`'s copy. If a similar "does this ordering
  actually achieve what its comment claims" question comes up for the
  other two consumers, it needs checking independently, three times.
- Any future fix to the underlying algorithm (e.g., if the one-mediator-
  per-model assumption the doc comment relies on — "Levels form a
  simple chain" — ever changes, per the multi-parent-mediator design
  discussion) needs applying in three places, and a missed one wouldn't
  fail loudly; it would just silently keep the old behavior in whichever
  file didn't get updated.
- It's indirect evidence that this really is a missing piece of
  `MMultiLevelModel`'s own public API, not three independent, coincidentally-
  similar needs — three different subsystems all reached for the exact
  same fix.

### Fix implemented

Moved the method onto `MMultiLevelModel` itself, as `levelsInHierarchyOrder()`
(the name floated above) — a real public method, body unchanged from the
three copies, now reading `this.mediators()`/`this.models()` instead of
taking an `MMultiLevelModel` parameter:

```java
public List<MModel> levelsInHierarchyOrder() {
    Map<String, MModel> parentOf = new HashMap<>();
    for (MMediator mediator : mediators()) {
        if (mediator.getParentModel() != null) {
            parentOf.put(mediator.getCurrentModel().name(), mediator.getParentModel());
        }
    }
    Map<String, Integer> depth = new HashMap<>();
    for (MModel model : models()) {
        int d = 0;
        String curName = model.name();
        Set<String> seen = new HashSet<>();
        while (parentOf.containsKey(curName) && seen.add(curName)) {
            curName = parentOf.get(curName).name();
            d++;
        }
        depth.put(model.name(), d);
    }
    List<MModel> levels = new ArrayList<>(models());
    levels.sort(Comparator.comparingInt(m -> depth.getOrDefault(m.name(), 0)));
    return levels;
}
```

All three private copies deleted; all three call sites now read
`mlm.levelsInHierarchyOrder()` / `e.levelsInHierarchyOrder()` instead of
`orderedLevels(mlm)` / `orderedLevels(e)`. This also gives
`getParentModel(String)` (this file's other "levels"-tagged, now also
fixed, entry) a proper sibling on the same class, closing both halves of
the gap this entry originally described: `MMultiLevelModel` now has a
real, correct, public way to ask for the hierarchy as a whole, not just
"the alphabetical list" (`models()`) or "this one model's parent"
(`getMediator(name).getParentModel()`).

### Verification

- **Confirmed the refactor changed nothing observable**: regenerated
  `examples/CatMLM/202608282120-C14_diagram.puml` by calling
  `PlantUmlDiagramGenerator.generate()` directly against the same
  compiled model and diffed it against the already-checked-in file —
  byte-for-byte identical. (This also means the diagram entry above's
  vertical-layout bug is still exactly as described; this fix is purely
  about the duplication, not a second attempt at that one.)
- Two new tests in `MLMCreationTest.java`:
  - `testLevelsInHierarchyOrderIsParentFirstNotAlphabetical` — same
    "child sorts alphabetically before its actual parent" shape as the
    `getParentModel` regression test, asserting `levelsInHierarchyOrder()`
    returns `[Zebra, Alpha]` while `models()` on the same object returns
    `[Alpha, Zebra]` — demonstrating this is a genuinely different (and
    correct) ordering, not a no-op wrapper.
  - `testLevelsInHierarchyOrderHandlesThreeLevelChain` — a real 3-level
    chain (`AB` -> `CD` -> `EF`, the existing
    `createMLMWithAttributeRenaming3()` fixture) sorts correctly by
    actual depth, not just "has a parent or not."
- Full `use-core` suite: 415/415 (413 -> 415, the two tests above; no
  regressions in any of the three now-shared call sites' own existing
  tests, e.g. `MMPrintVisitorMLMTest`).

---

## The Shell's `info levels` command prints models alphabetically with a decorative arrow that implies a hierarchy it never checks

**Tags:** levels, shell

**Status:** FIXED. Implemented and verified.

**Location:** `use-gui/src/main/java/org/tzi/use/main/shell/Shell.java:1049-1057`.

### The finding

```java
private void cmdInfoLevels() throws NoSystemException {
    MSystem system = system();
    Collection<MModel> models = ((MMultiLevelModel)system.model()).models();
    System.out.println("NONE");
    for (MModel model : models){
        System.out.println(" /\\ ");
        System.out.println(model.name());
    }
}
```

`models()` is the same alphabetically-ordered `TreeMap` values this
whole file's other "levels" entries are about — this command never
touches `fMediators` at all. Built a model to check directly rather than
relying on the `TreeMap` contract alone: "Zebra" is the actual root (no
parent), "Alpha" is its child (`mediator Alpha < Zebra`). Calling the
exact same `models()` this method calls prints `Alpha` first, then
`Zebra` — the child before the root it's actually under, with a
decorative `" /\ "` between them exactly as if it meant something. For
a real hierarchy of more than two levels, or a forest with more than one
root, this would print a plausible-looking but entirely wrong diagram —
not just unhelpful, actively misleading, since the arrow visually
implies a parent/child relationship between whichever two models happen
to be adjacent alphabetically.

### Fix implemented

`cmdInfoLevels()` now calls `levelsInHierarchyOrder()` (the entry above)
for the print order, and shows each level's *real* parent (via the now-
also-fixed `getParentModel(String)`) explicitly, mirroring the source
syntax directly (`mediator ID1 < ID2` / `< NONE`) instead of a decorative
arrow that implied an edge without checking one:

```java
private void cmdInfoLevels() throws NoSystemException {
    MSystem system = system();
    MMultiLevelModel mlm = (MMultiLevelModel) system.model();
    for (MModel model : mlm.levelsInHierarchyOrder()) {
        MModel parent = mlm.getParentModel(model.name());
        System.out.println(model.name() + " < " + (parent == null ? "NONE" : parent.name()));
    }
}
```

This handles a forest correctly for free: each root prints `<Name> <
NONE` on its own line, with no false chaining to an unrelated tree —
there's no shared arrow to mis-draw between two roots that happen to
print consecutively, since each line only ever states that one model's
own, real parent.

### Verification

`cmdInfoLevels()` itself has no existing test coverage to extend (no
test directory exists for `use-gui`'s `Shell` at all), and it's a
`private` method that prints directly to `System.out` from a live
session — not easily unit-tested in isolation. Verified the actual new
logic instead, by replicating it exactly against two compiled models:

- The same "Alpha/Zebra" case as the `getParentModel` regression test:
  ```
  Zebra < NONE
  Alpha < Zebra
  ```
- A genuine forest — one two-level chain (`P1 < P3`) plus two unrelated
  standalone roots (`P2`, `Solo`) — confirming no false chaining, the
  main defect this fix was for:
  ```
  P2 < NONE
  P3 < NONE
  Solo < NONE
  P1 < P3
  ```

`mvn -pl use-gui -am compile` succeeds. No `use-core` test regressions
possible (this change is confined to `use-gui`, which has no test suite
of its own to run).
