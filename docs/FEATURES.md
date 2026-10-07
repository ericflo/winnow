# Winnow, feature by feature

The [README](../README.md) has the overview. This is everything, grouped.

## Filtering

- **Five categories:**
  - **personal**: people you know;
  - **transactional**: set off by something you did (a code, an order, an appointment), or a notice from someone you already deal with that sells nothing (a landlord's "test your heater before winter");
  - **marketing**: a business wanting you to buy;
  - **political**;
  - **spam**: junk, scams and phishing from strangers.
- **Actions:** each category maps to one, which you can change:
  - **notify**;
  - **silence**: inbox, no notification;
  - **filter**: Filtered, no notification.
- **The filtered banner** says what Winnow decided and who decided it, with **Why?**, **Not spam**, **Filter sender** and **Report**. Report forwards the text to your carrier's 7726 spam service after you confirm.
  - Links in spam can't be tapped.
  - A sender who sent fraud has every link of theirs turned off.
- **Filtered words** (Settings) send a stranger's text that uses one straight to Filtered, decided on the phone.
  - Whole words only, so "vote" doesn't catch "devoted".
  - Contacts and people you've texted aren't affected.
- **Clear out old filtered texts** (optional) moves filtered conversations untouched for a month to Recently deleted. It never moves one you've written in, pinned or starred.
- **Daily summary** (optional) says each evening how many texts were kept out of the inbox, and opens Filtered. Nothing is sent on a quiet day.
- **Sender rules:** always allow or always filter a sender. Android's block list is honored.

## Teaching it your texts

- **Label** anything, and Winnow moves the conversation where your settings file that category, refits the on-phone model on the spot, and offers Undo. Ways to label:
  - a conversation (its ⋮ menu, or swipe left);
  - one message (long-press);
  - many at once (multi-select, then the tag).
- **Sender rules and labels:** labeling a whole conversation also removes a sender rule that disagrees with it. Labeling one message leaves the rule and says it's still there.
- **Train Winnow** works through your backlog in rounds of 20 conversations with people outside your contacts.
  - Winnow shows its guess for each, mostly where it's least sure, plus a few at random.
  - Tap ✓ when it's right, **All right** for a whole group, or the guess to change it.
  - Guesses update as you answer, so fixing one text moves its lookalikes.
  - Finishing a round labels what you answered and retrains. The summary lists where it was wrong.
  - A half-answered round survives Winnow being closed.
  - **Label more like these** builds a round from the conversations that read most like your labels of a category.
- **Labels from before a category change** wait in Train, first in every round:
  - Labels from before the six-category taxonomy show "You said X before", to confirm or change.
  - Reminder was later taken out. Its labels were cleared rather than moved, because some were marketing, and come back to be labeled again.
- **Your labels of each sender** follow that sender, because texts that read alike can mean different things depending on who sent them.
  - Three or more of a sender's texts labeled one way decide their next texts before any service is asked, unless you text with them.
  - Otherwise your labels lean the model's answer, and outweigh a service answer you've never given that sender.
  - Winnow's model → Inside lists every sender you've labeled and sets how much your labels count.

### Let a classifier service label your backlog first

- **When it's offered:** with a service set up (Jev via TypeSafe or OpenRouter) and marked zero data retention, Train offers to send the newest three received texts of each conversation with someone who isn't a contact.
- **Before anything is sent,** a confirmation says how many texts and what leaves the phone.
- **What it sends:** texts get the same redaction as live ones. Contacts, people you've written to, codes and senders with a rule stay on the phone.
- **Zero retention:**
  - On OpenRouter, every request asks for zero-retention endpoints only (`"provider": {"zdr": true}`).
  - On TypeSafe directly, zero retention is TypeSafe's own policy, which you confirm in Settings; TypeSafe offers it only to enterprise accounts.
- **How Jev answers:**
  - Jev picks among 79 finer kinds of text ("fake toll notice", "landlord maintenance notice", "donation ask"), and their probabilities add up into the five categories.
  - Up to three of your own labeled texts per category go along as examples, redacted the same way.
  - Train shows the finer kind beside Jev's answer ("Jev said Spam · toll phishing").
- **How its answers are used:**
  - They teach the on-phone model at about a third of the weight of your labels. Yours always win, and replace Jev's on the same text.
  - Rounds put first the conversations where Jev and the model disagree.
  - Jev's labels never count as right answers on the accuracy screen, and Settings can forget just those.
- **Cost:** about $0.0001 a text, so a backlog of a few thousand is well under a dollar.
- **How a run behaves:**
  - It continues with the screen off (a foreground service with Stop).
  - It stops itself if the service stops answering, and says why: a refused key, no credit, rate limiting, an outage.
  - It resumes where it left off.
- **Every run is kept:** for each text, what Jev said, what the model thought before and thinks now, and your label (Train → Past runs).

### Seeing what decided each text

- **Why?** (long-press a received text, or the banner's Why?) shows:
  - who decided: a rule, the on-phone model and which fit, or the service with its finer kind and timing;
  - why it was them;
  - what each of them thought, side by side;
  - how the category became an action;
  - what the text taught the model.
- **Activity** shows who decided your texts day by day, what each decision did, how often you changed it, and every decision with its reason.

### Winnow's model

- **Overview:**
  - what the model learned from;
  - how it does on your own labels, cross-validated;
  - how you, the service and the model agree, pair by pair;
  - who decided your texts;
  - an honest answer to whether your labels make the service better: they only reach it as examples in backlog runs.
- **Evaluate:**
  - score the model as it is now, as it ships, on your labels only, with another weight for service labels, or as any kept fit, side by side with every miss;
  - replay how it learned;
  - measure what your examples change in Jev's answers.
- **Lab:**
  - design and train models on the phone: the personal layer, a linear model retrained from scratch, a neural network, or a blend, every setting yours;
  - optional signals beyond the words: word pieces, words by who sent them, when a text came, percents, times and codes, groups of words that mean alike and what a whole text means (both from GloVe's public-domain word vectors, shipped with the app), and the labeled texts a text reads most like;
  - score them on conversations they haven't seen, and put one in use;
  - **Sweep** tries recipes round after round, steered by the service or by the phone, and keeps the best;
  - every miss is broken down: how far to the target, and what each kind of miss has in common.
- **Inside:**
  - how the model is built, with nothing left out;
  - what your teaching changed most;
  - how it reads any text you type, with odds per category and what pulled where.
- **History:** every fit.

### How accurate is Winnow?

**Filtered → How accurate is Winnow?** is about your texts only.

- **On your texts:** Train guesses you answered, and texts judged on arrival that you labeled later.
- **Charts from your labels:**
  - ROC and precision–recall curves, which move with a threshold explorer;
  - calibration ("does 90% sure mean right 90% of the time?");
  - per-category precision, recall, F1 and AUC;
  - the confusion matrix and coverage.
- **How they're scored:** cross-validated on the phone, each text by a model refit without its conversation. They appear at 20 labeled texts in two or more categories.

## Messaging

- **SMS and MMS:**
  - group conversations threaded correctly;
  - photos in and out, downscaled to the carrier's MMS limit, and long texts sent as MMS where the carrier asks;
  - a full-screen photo viewer;
  - voice messages, video, GIFs and keyboard stickers;
  - MMS subjects, sent and received.
- **Attach:** photos (rotate, crop or draw first), camera, video (re-encoded or trimmed to fit an MMS), voice message, contact card, location (one map link, nothing tracked), subject.
- **Links:**
  - Link previews are opt-in and only fetched for your own links and texts from contacts or people you've texted.
  - Tracking numbers link to the carrier.
  - Addresses open in Maps.
  - Dates and flight numbers get Android's on-device suggestions.
  - Phone numbers in a message offer Call, Send message, Add contact and Copy.
- **Messages:**
  - react with any emoji (sent as `Loved "…"`, shown as tapbacks on iPhones);
  - copy all or part, forward, share, star, delete, details;
  - "Copy code" for verification codes;
  - multi-select;
  - **Remind me** brings a message back later as a notification;
  - **Starred** collects starred messages from every conversation.
- **Composing:**
  - New chat with recent people first, Create group, Add people;
  - Reply privately in a group, and **Send separately** (each person gets their own text);
  - drafts that stick, attachments included;
  - an SMS segment counter, and optional **simple characters**, which swap curly quotes and accents when that keeps a text from costing an extra segment;
  - scheduled send: move, edit or send now;
  - optional undo send of 5 or 10 seconds;
  - quick replies, suggested replies from Android's on-device Smart Reply, and texting email addresses as MMS.
- **Conversations:**
  - pin, archive, mute (an hour, 8 hours, a day, or until you turn it back on), mark read or unread, delete, block;
  - name a group, Add contact, Add to home screen, export to a text file;
  - full-text search, also within one conversation;
  - Go to date, and a "new messages" divider;
  - inbox chips (Unread, Personal, Updates, Offers);
  - reply and birthday reminders;
  - swipe actions you choose;
  - optional deletion of one-time codes after a day.
- **Recently deleted:** conversations and messages wait 30 days, photos and Winnow's decisions included.
- **Notifications:**
  - Android conversation notifications with their own sound per conversation;
  - inline Reply, Mark as read, Copy code, and **Spam** on a stranger's text that got through;
  - photos only from contacts and people you've texted;
  - chat bubbles, and Android Auto (read aloud, answer by voice).
- **Failures:** a failed send says so in the inbox and in a notification, and comes back to the composer. What's in the way, such as airplane mode or mobile data off for MMS, is said before a message fails. Failed MMS downloads are retried by themselves and explain why they failed.
- **Dual SIM:** a badge in the composer picks the SIM, and each conversation remembers its own.
- **Big screens:** two panes side by side on tablets and foldables. Optionally, Enter sends. Keyboard shortcuts: Ctrl+N, Ctrl+F, Ctrl+,.
- **Display:** light, dark or system theme, text size, and pinch to zoom a conversation.
- **Elsewhere on the phone:** a home-screen widget, Direct Share targets, and Share to Winnow from any app.
- **Backup and restore:**
  - one zip file you keep: messages, photos, decisions, drafts, sender rules and settings, never API keys;
  - restoring only adds what's missing;
  - weekly automatic backups to a folder you pick;
  - optional AES-256 backup password;
  - import from and export to SMS Backup & Restore's XML.
- **RCS:** Android gives RCS to Google Messages alone. Winnow marks conversations that were RCS, explains how to turn it off, and lets you name people known only by an RCS id.

## Privacy

- **Decided on the phone, never sent:** texts from saved contacts (work-profile contacts too), from people you've texted, and verification codes.
  - If Winnow can't read your contacts, it can't tell a contact from a stranger. It sends nothing anywhere until it can, and says so.
- **What a service sees** is redacted:
  - runs of 4+ digits become `####`;
  - emails become `[email]`;
  - links become their domain.
  - The sender's number is only shared if you turn that on.
- **Zero-retention only** skips any provider you haven't marked as keeping no data.
- **Decide on this phone when it's sure** keeps texts the model is 95%+ sure about local. On the test texts that's about two thirds of them, almost all called right.
- **On this phone only** sends nothing. The model filters only when it's at least 85% sure, and silences otherwise.
- **When things fail:** if no service answers in time, the model decides. If classification fails altogether, the text arrives with a notification.
- **Locking:**
  - API keys are encrypted with the Android Keystore.
  - **Lock Winnow** asks for your fingerprint, face or screen lock after a minute away, and blanks Winnow in recents.
  - **Hide texts on the lock screen** is optional.
- **Labels** are kept as hashed word fingerprints, never the text, and go into backups. Settings → **Forget** undoes all teaching.

## The on-phone model's filtering rule

- **When it filters:** an unwanted category at 85% confidence or more.
- **Spam needs a hook:** a link off a company's real site, money, a number to call, or payment or code talk. Spam without a hook is silenced instead, because "hi, is this David?" reads exactly like a real person on a new number.
- **When it's unsure:** under 60% sure, such a text isn't even silenced, since it can't defraud anyone and might be a friend.
- **How it scores:** on the hand-written test texts it filters 0.4% of wanted ones and keeps 92.5% of unwanted ones quiet. The full numbers are in [classifier/training/REPORT.md](../classifier/training/REPORT.md).
