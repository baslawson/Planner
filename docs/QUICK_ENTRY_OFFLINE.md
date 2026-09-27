# Offline Quick entry

Typing is processed on the phone. AI is optional and never runs automatically.
Single and Multiple entries share the parser and review controls.

Examples of everyday wording:

- `Dentist day after tomorrow 3pm`
- `Call plumber in two days` or `a week from today`
- `Dentist tomorrow 3pm notify me 10 minutes before`
- `Dinner tomorrow 6pm remind me half an hour before`
- `Dentist Friday—actually Saturday at 3pm`
- `Dinner tomorrow evening` — keeps Dinner and tomorrow, then requires Choose time.

Relative dates accept numeric counts or words one through twelve, plus a/an. The unfinished draft's reference
date is retained across reopening. Adjacent named/relative/ISO dates joined by “actually” are explicit
corrections; unrelated multiple dates still need correction. Invalid replacement dates remain errors.
Reminder aliases share the existing reminder model and its bounds; nothing schedules until you save.

Recognised vague time phrases such as tomorrow morning/evening, tonight, this afternoon and after lunch
never pick a clock time automatically. Choose a specific time using the existing preview control. Conflicting
time phrases still require editing. Tasks use due dates; switch to Event for a time, or keep the words literally.
Other unsupported schedules, such as every weekday, still need a supported repeat or literal text.

Quote title/place words you want left untouched, or use Details → Adjust recognised text → Keep in title.
Manual corrections survive unrelated title edits; editing the corresponding scheduling phrase reconsiders
that correction. Check the preview, then tap Add. Closing keeps the unfinished draft.

More flexible offline wording:

- `Study tomorrow 3.30pm for two hours`
- `Study tomorrow 3 p.m. for an hour and a half`
- `Call tomorrow 15h30 for a quarter of an hour`
- `Meeting Friday at 3pm—actually 4pm`

Durations accept number words one through twelve. Clock spellings also work in explicit ranges;
ambiguous ranges still need AM/PM clarification. Adjacent clock times joined by “actually” use the final
time; unfinished corrections and conflicting ranges require editing. All recognised details use the
existing preview. Quotes and Keep in title still protect literal text.

While typing a date word, near-miss spellings such as `tommorow` or `wedensday` offer a suggestion in
the existing suggestion row. Tap to replace that word; nothing is corrected automatically. Suggestions
respect quoted/literal text and the cursor, and leave the rest of the sentence intact.
