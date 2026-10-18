# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

Tracker: **GitHub Issues on `Qy14172945/MusicHud`** (see [issue-tracker.md](./issue-tracker.md)).

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the right-hand column to match whatever vocabulary you actually use.

## Wayfinder labels

`/wayfinder` adds a second, separate label family. These are not triage roles — don't
mix them into the table above:

| Label                 | Meaning                                                     |
| --------------------- | ----------------------------------------------------------- |
| `wayfinder:map`       | The map issue itself (one per wayfinding effort).           |
| `wayfinder:research`  | Ticket: AFK research to surface a fact a decision waits on.  |
| `wayfinder:prototype` | Ticket: HITL prototype artifact to react to.                 |
| `wayfinder:grilling`  | Ticket: HITL conversation (the default ticket type).         |
| `wayfinder:task`      | Ticket: manual work that unblocks a decision.                |

A ticket carries exactly one `wayfinder:<type>` label, plus whatever triage label its
state warrants. When `wayfinder:*` labels are in play, they are the primary grouping;
triage roles still apply on top.
