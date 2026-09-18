# Open Questions About the System

A running list of questions worth having a clear, verified answer to —
some already investigated (answered inline, with pointers to where),
some still genuinely open. Companion to `proposed-changes.md` (concrete
proposed fixes/extensions); this file is for understanding first,
before deciding what — if anything — to change.

**Format per entry:** the question, a `Tags:` line, then either an
`Answer:` (verified against the actual code) or a `Status: open` note
with whatever leads exist so far.

---

## (1) How is the order of layers/levels computed?

**Tags:** #levels #layers

**Answer:** It isn't computed as an order at all — there's no
topological sort, and no numeric depth/potency field anywhere in
`org.tzi.use.uml.mm`. Resolution happens in two independent phases:

1. **Models** are built in file/declaration order (`ASTMultiModel.gen()`'s
   per-`internal_model` loop), but order genuinely doesn't matter: a
   model's own content (classes/associations/constraints/enums) can
   never reference another model at all, so every constituent `MModel`
   ends up fully built and name-addressable regardless of what order it
   was declared in.
2. **Mediators** (the parent/child, "instance-of" edges) resolve their
   own declared parent purely **by name**, against the already-complete
   set of models from phase 1 — `mMultiLevelModel.getModel(parentModelName)`
   for plain MLM-USE, `classesByLevel.get(level.parentName().getText())`
   (from a full pre-pass over every level) for CatMLM's desugaring. No
   positional or topological dependency between mediators exists.

Confirmed empirically (see `known-issues.org`'s "Addendum: CatMLM
level-order..."): a bottom-level clabject can instantiate a top-level
class directly, skipping a middle level entirely, and it compiles clean
and reports `WellDefined` — there is no adjacency check anywhere.

A historical bug in exactly this area was already found and fixed:
`MMultiLevelModel.checkState()` used to iterate `this.models()` (a
`TreeMap<String,MModel>`, i.e. *alphabetical* by model name) and pair
each with "the previous one" positionally — silently wrong whenever
level names didn't happen to sort in hierarchy order. Fixed to use each
mediator's own `getParentModel()` directly instead.

**A second instance of the same bug existed** —
`MMultiLevelModel.getParentModel(String)` (note: different method from
`MMediator.getParentModel()`) used to infer a parent by declaration-order
position, live in `UseMLMApi.createMediator`. **Now fixed** — see
`proposed-changes.md`'s "levels"-tagged entry for the full writeup,
including the regression tests added to lock this in.

---

## (2) What data structure maintains it?

**Tags:** #levels #layers

**Answer:** Two separate structures in `MMultiLevelModel`, easy to
conflate but serving different purposes:

- `fModelsList: List<MModel>` — every constituent model, in
  *declaration* order. Purely positional bookkeeping; **not** the
  hierarchy. (This is exactly the list `getParentModel(String)` walks
  positionally — see Q1's last paragraph for why that's fragile.)
- `fMediators: Map<String, MMediator>` — the actual parent/child edges,
  keyed uniquely by the **child** model's name. `addMediator()` throws
  if a second mediator is added for the same child — so today, a model
  can have at most one parent, full stop. Each `MMediator` object itself
  carries a resolved reference to its one parent `MModel`
  (`MMediator.getParentModel()`), set once at mediator-generation time.

So "the hierarchy" as the system actually knows it is a set of named
edges (a forest, given the one-mediator-per-child constraint — though
even that's a *constraint*, not something inherent to how edges are
stored; nothing about `Map<String, MMediator>`'s value type prevents
generalizing the key to allow more than one edge per child, only the
key's uniqueness does). There's no single object anywhere representing
"the level order" as a sequence or a tree — every consumer
(`checkState()`, `checkWellDefinednessState()`, `powerTypes()`) walks
`mediators()` and follows each one's own parent reference directly.

---

## (3) What is the metadata query about levels, and how is the answer computed?

**Tags:** #levels #layers

**Status:** open — kept in the file at the user's explicit request, to
revisit later. Best-guess interpretation below, not confirmed against
what "the metadata query" actually refers to. Found two real candidates
in the interactive Shell that both plausibly answer "give me information
about the level structure," worth distinguishing:

- **`info levels`** (`Shell.cmdInfoLevels()`,
  `use-gui/.../main/shell/Shell.java:1049`) -- **now fixed.** It used to
  list every model via `((MMultiLevelModel)system.model()).models()` and
  print `"NONE"` followed by `" /\ "` + the model's name for each one --
  since `models()` is inherited from `MMultiModel`'s
  `TreeMap<String,MModel>`, that printed in **alphabetical order** with
  an upward arrow between *every consecutive pair* regardless of whether
  they were actually parent and child, confirmed empirically (built a
  model where "Alpha" is the child of "Zebra" the root, and the old code
  printed "Alpha" first, "Zebra" second). Now calls
  `levelsInHierarchyOrder()` for the order and shows each level's real
  parent explicitly (`Name < Parent` / `Name < NONE`), which also
  handles a forest of more than one root correctly -- see
  `proposed-changes.md`'s "levels, shell"-tagged entry for the fix and
  its verification.
- **`info level <name> [-classes|-associations|-mediator|-powerTypes]`**
  (`Shell.cmdInfoLevel()`, same file, line 1059): the real per-level
  metadata query — looks the named model up via
  `((MMultiLevelModel)system.model()).getModel(modelName)`, then, per
  flag, prints its classes/associations (via `MMPrintVisitor`), its own
  mediator (`getMediator(modelName)` — the correct, name-keyed lookup,
  not the positional one), and its power types. This one does reflect
  the real mediator-based structure, just for one level at a time rather
  than the whole hierarchy at once.

If "the metadata query" refers to something else entirely (an OCL-level
introspection operation, a GUI diagram, an ASSL/validator-facing API) —
say which, and this entry can be corrected and re-investigated properly
rather than left as a guess.

### Where the data comes from

Traced both commands down to their actual source, not just the
immediate Java call. Everything either one prints is a pure read over an
object graph built exactly **once**, at compile time when the model is
`open`ed — nothing is re-derived or re-parsed when the Shell command
itself runs. Three distinct stores, all populated in a single top-to-
bottom pass by `ASTMultiLevelModel.gen()`:

1. **The models-by-name store** (feeds `info levels`, and `info level
   <name>`'s initial lookup): `ASTMultiModel.gen()`'s per-`internal_model`
   loop builds one `MModel` per `model X ... end` block in the source
   and registers it (`MMultiLevelModel.addModel()`) into the inherited
   `MMultiModel` name-keyed collection.
2. **The mediators-by-child-name store** (feeds `-mediator`): for each
   `mediator X < Y ... end` block (or CatMLM's fused header, already
   desugared to the same AST by this point), `ASTMediator.gen()` resolves
   its parent via `mlmContext.getParentModel()` — set earlier by looking
   the parent's name up in the *exact same* store from step 1, not
   independently. Each `clabject Child : Parent ... end` inside becomes
   an `ASTClabject`, whose `gen()` resolves **both ends by name**
   (`mlmContext.getParentModel().getClass(parentName)`, current model's
   `getClass(childName)`) and builds an `MClabject` holding direct
   references to the two already-built `MClass` objects, stored in the
   mediator's own `fClabjects: Map<String, MClabject>`.
3. **`-powerTypes` is the one place doing any live computation**, but a
   trivial one: `MMediator.powerTypes()` just projects `.parent()` off
   every clabject already sitting in `fClabjects` from step 2 — no
   separate field, recomputed fresh on every call, but over data fixed
   at compile time.

So: source text -> one-time name-based resolution during
`ASTMultiLevelModel.gen()` -> a static object graph (models map,
mediators map, each mediator's own clabjects/assoclinks maps) -> the
Shell just walks references. Every one of these lookups is by explicit
name against an already-complete store, never by position — the same
finding as Q1/Q2, now traced one level deeper, down to where the
objects actually come from rather than just how they're organized.
