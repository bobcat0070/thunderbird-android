# ADR: Microsoft Graph Backend for Microsoft 365 Accounts

- Status: **Proposed**

## Context

Microsoft 365 mailboxes are increasingly unreachable over IMAP and SMTP. Tenants disable IMAP and POP by policy, and
Microsoft disables SMTP AUTH by default for new tenants. For a large share of Microsoft 365 users a conventional
account configuration cannot connect at all, no matter how it is set up.

The app already supported OAuth against `outlook.office365.com` for IMAP and SMTP, so Microsoft 365 accounts appeared
to be supported. In practice that path fails whenever the tenant has turned those protocols off, and the failure looks
to the user like a broken password.

Microsoft Graph is the API Microsoft actually maintains for these mailboxes. It covers both retrieval and submission,
and it stays available when IMAP and SMTP are disabled.

## Decision

Add `:backend:graph`, a `Backend` implementation that talks to the Microsoft Graph mail API, and select it for
accounts whose incoming server type is `graph`.

### Fitting Graph into the existing model

- **Messages stay MIME.** Message content is fetched as raw RFC 5322 through `/messages/{id}/$value` and sent through
  `/me/sendMail` as base64 MIME. Graph's JSON message model cannot round-trip arbitrary MIME, and routing content
  through it would lose structure the app already handles correctly. Graph is therefore used as a transport, not as a
  message model.
- **Envelopes come from JSON.** Sync lists messages through the JSON API and downloads full content on demand, so a
  sync costs one request per page rather than one message download per message. The `bodyPreview` property is stored
  as the message text so the message list can show preview lines without downloading anything.
- **Synchronization is incremental.** Each folder keeps a delta token, so a sync after the first returns only what
  changed. An idle folder costs a single request that returns an empty collection.
- **Message ids are not stable.** Graph reassigns a message id when the message changes folder, which is why move and
  copy report the new ids back to the caller.

### Detection

`MicrosoftGraphDiscovery` runs ahead of Autoconfig and selects Graph when the domain's MX records point at Exchange
Online, or when the address is an Outlook.com domain. MX records were chosen over Microsoft's account lookup endpoints
because they disclose only the domain, never the address being configured — and because the identity endpoints answer
for any domain, since Microsoft creates unmanaged tenants on demand, so they cannot tell a Microsoft 365 mailbox from
any other.

## Outcomes

### Positive Outcomes

- Microsoft 365 mailboxes work even when the tenant has disabled IMAP, POP and SMTP AUTH, which is the case the
  previous IMAP/SMTP path could not serve at all.
- Synchronization is incremental, so a routine sync of an unchanged folder is a single request returning nothing.
- New mail arrives within about a minute rather than up to fifteen, without any server-side infrastructure.
- Message content still flows through the existing MIME pipeline, so composing, viewing and storage behave the same
  as for IMAP accounts.

### Negative Outcomes

#### Push is polled, not delivered

Graph delivers change notifications by POSTing to a publicly reachable HTTPS endpoint; the alternatives are Azure
Event Hubs and Event Grid. All of them are server-side, and a device has no URL to receive them at. Receiving real
notifications would require operating a relay that forwards them to devices through a push service — an
infrastructure and privacy decision for the project, and one that would not work in `foss` builds, which deliberately
exclude Google Play services.

Timeliness is instead achieved by polling frequently from the existing push foreground service, the same one IMAP
IDLE runs in. Background sync is scheduled with `PeriodicWorkRequest`, which the platform clamps to fifteen minutes;
the push service is not subject to that clamp, so a Graph account with a push-enabled folder is checked every minute
by default.

Each poll reads only the message counts of the pushed folders, batched into one request, and reports a folder as
changed when its counts moved. Synchronization is left to the caller, so the poll does not consume the delta token.
The counts miss a change that leaves both untouched, such as an edit in place; the periodic sync still catches those.

Like IMAP push, this requires the user to grant the "Alarms & reminders" permission (`SCHEDULE_EXACT_ALARM`) and to
enable push on a folder. Without the permission `PushController` disables push for every account, Graph and IMAP
alike.

#### Reduced fidelity compared with IMAP

- Graph has no equivalent of the `\Answered` flag on a message, so replies are not reflected in the flag state.
- There is no expunge step; a delete moves the message to Deleted Items.
- Individual MIME parts cannot be fetched, so opening a message downloads it whole.
- Messages larger than 4 MB cannot be sent inline and would need an upload session, which is not implemented.
- Folders created in the app are not created on the server.

#### Requires an app registration with Graph permissions

The OAuth configuration requests the delegated Microsoft Graph scopes `Mail.ReadWrite` and `Mail.Send`. The app
registration behind the client id must have them granted, and tenants that require admin consent need an
administrator to approve the app before sign-in succeeds.

#### Operational cost

Graph throttles per request, so operations spanning many messages are sent through `$batch`. The first sync of a
folder enumerates it to obtain a delta token; that enumeration is bounded by date so it stays proportional to the
number of visible messages rather than to the size of the mailbox.
