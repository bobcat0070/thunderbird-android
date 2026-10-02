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
- **Large messages are assembled on the server.** Graph accepts raw MIME only up to 4 MB and cannot take more of it
  in pieces. A larger message is created as a draft without its attachments, which are then added to it - the big
  ones through an upload session - before the draft is sent. Creating the draft from MIME keeps every header the app
  wrote, including the ones that thread a reply.
- **Envelopes come from JSON.** Sync lists messages through the JSON API and downloads full content on demand, so a
  sync costs one request per page rather than one message download per message. The `bodyPreview` property is stored
  as the message text so the message list can show preview lines without downloading anything.
- **Synchronization is incremental.** Each folder keeps a delta token, so a sync after the first returns only what
  changed. An idle folder costs a single request that returns an empty collection.
- **Messages are identified by immutable ids.** The id Graph reports by default changes whenever a message changes
  folder, so a message moved by another client or a server rule looked like one message deleted and another
  arriving: its downloaded body was lost and anything referring to it pointed at nothing. Every request therefore
  asks for immutable ids (`Prefer: IdType="ImmutableId"`), which stay with a message for as long as it is in the
  mailbox. Move and copy still report the ids back to the caller, which for a move is now the id the message
  already had. Folder ids were always stable.
- **Replied and forwarded follow Outlook.** Exchange keeps one "last action" per message rather than a flag for each.
  A reply or forward made here is written the way Outlook writes it, so Outlook shows the arrow. Reading it back
  takes a separate request after each delta round, because Microsoft 365 refuses a delta request that expands
  extended properties, whatever the documentation says.
- **Importance is a header, categories are not part of the message.** The importance Graph reports is stated in the
  stored message's `Importance` header, the same place the app reads it for any other account and writes it when a
  message is composed. Outlook categories have no place in a message, so they are stored beside it and kept current
  by delta sync, also for a message whose body has been downloaded. A change made here is written back with a patch
  of the message's categories, queued like a flag change so that it survives being offline.

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

- Replied and forwarded cannot both be recorded: Exchange keeps only the last action, so a message replied to and
  then forwarded reads as forwarded. Nor can either be cleared on the server, so clearing one stays on the device.
- There is no expunge step; a delete moves the message to Deleted Items.
- Individual MIME parts cannot be fetched, so opening a message downloads it whole.
- Folders created in the app are not created on the server.
- An immutable id still changes when a message is moved to an archive mailbox, or exported and imported again.
- The colours of Outlook categories, and the list of categories a mailbox defines, are mailbox settings. Reading
  them needs the `MailboxSettings.Read` permission, which the app does not request, so a category is shown in a
  colour worked out from its name and only categories already seen on synchronized mail are offered when assigning
  one. A category defined in Outlook but not yet used has to be typed.
- The importance of a message that was already received cannot be changed from the app.

#### Requires an app registration with Graph permissions

The OAuth configuration requests the delegated Microsoft Graph scopes `Mail.ReadWrite` and `Mail.Send`, and
`Contacts.Read` and `People.Read` for completing addresses. The app registration behind the client id must have them
granted, and tenants that require admin consent need an administrator to approve the app before sign-in succeeds.
Every further scope is a permission each user has to consent to again, which is why features that would need one,
such as category colours, do without.

#### Converting ids that are already stored

Mail synchronized before immutable ids were asked for is stored under the old ids, and a sync that reported the same
message under another id would store it twice. So before a folder is synchronized again, each message stored for it
is looked up by the id it has and stored again under the immutable id Graph answers with. A message Graph no longer
finds has been deleted or moved since the last sync and is removed, unless the account does not follow remote
deletions. Delta tokens are valid for either kind of id, so the folder is not enumerated again.

This costs one request per stored message, twenty to a batch, once per folder. Graph can convert a thousand ids in
one call (`translateExchangeIds`), but only for an app permitted to read the user's profile (`User.Read`), which
would be one more scope for every user to consent to. Progress is recorded as the conversion goes, so a folder large
enough to be throttled part way carries on at the next sync instead of starting over; until it is done, that
folder is not synchronized.

#### Operational cost

Graph throttles per request, so operations spanning many messages are sent through `$batch`. The first sync of a
folder enumerates it to obtain a delta token; that enumeration is bounded by date so it stays proportional to the
number of visible messages rather than to the size of the mailbox.
