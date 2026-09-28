# Offline Quick entry

Typing is processed on the phone. AI is optional and never runs automatically.
Enter one entry at a time; Add another keeps Quick entry open for the next.

Examples of everyday wording:

- `Dentist day after tomorrow 3pm`
- `Call plumber in two days` or `a week from today`
- `Dentist tomorrow 3pm notify me 10 minutes before`
- `Dinner tomorrow 6pm remind me half an hour before`
- `Dentist Friday—actually Saturday at 3pm`
- `Dinner tomorrow evening` — keeps Dinner and tomorrow, then requires Choose time.

Relative dates accept numeric counts or words one through twelve, plus a/an, and `wk`/`wks` and fortnights
(`in 2 wks`, `in a fortnight`). `tmrw`, `tmr`, `tomoz` and the common misspellings `tomorow`, `tommorow` and
`tommorrow` mean tomorrow. The unfinished draft's reference
date is retained across reopening. Adjacent named/relative/ISO dates joined by “actually” are explicit
corrections; unrelated multiple dates still need correction. Invalid replacement dates remain errors.
Reminder aliases share the existing reminder model and its bounds; nothing schedules until you save.

Recognised vague time phrases such as tomorrow morning/evening, tonight, this afternoon and after lunch
never pick a clock time automatically. Choose a specific time using the existing preview control. With a clock
time beside a part of the day, both are used and the part of the day settles am/pm: `Movie tonight 8pm`,
`Run tomorrow morning at 7` (07:00), `Dinner tomorrow evening 7:30` (19:30). A time outside that part of the day
(`tomorrow evening 3pm`) needs correcting, and meal or work phrases (`after lunch 2pm`) still require editing.
Tasks use due dates; switch to Event for a time, or keep the words literally.

- `Meeting next week Tuesday` or `Tuesday next week` — the same as next Tuesday.
- `Mow lawn this weekend` — this Saturday (today, on a Saturday or Sunday).
- `Rent due end of month` (also `by the end of the month`) — the last day of this month.

Other unsupported schedules, such as every weekend, every other day, next week on its own or the end of the week,
still need a specific date, a supported repeat or literal text.

**Title box (optional).** Words typed in Title are always the title and are never read as a date or time, so
`Monday club` with When `Friday 6pm` works. The When box is read as usual; words in it that are not scheduling
(`with Bob`) are added after the title. Leave Title empty to type everything in one box as before. Quotation marks
are removed from a typed title. For AI, the title is sent in quotes on the same line.

Quote title/place words you want left untouched, or use Details → Adjust recognised text → Keep in title.
Manual corrections survive unrelated title edits; editing the corresponding scheduling phrase reconsiders
that correction. Check the preview, then tap Add. Closing keeps the unfinished draft.

More flexible offline wording:

- `Study tomorrow 3.30pm for two hours`
- `Study tomorrow 3 p.m. for an hour and a half`
- `Call tomorrow 15h30 for a quarter of an hour`
- `Meeting Friday at 3pm—actually 4pm`
- `Tea half past 3`, `Meeting quarter to 5pm`, `Train 5 past 7pm`, `Call ten to 12pm`, `Meeting at 3 o'clock`
- `Meeting @ 3pm`, `Dinner 7pm @ Nandos` — `@` on its own works like at; an email address such as bob@example.com
  stays in the title.

Durations accept number words one through twelve. In spoken times, only words go before "to" (`quarter to 5`),
because `4 to 5pm` is a range. Clock spellings also work in explicit ranges;
ambiguous ranges still need AM/PM clarification. Adjacent clock times joined by “actually” use the final
time; unfinished corrections and conflicting ranges require editing. All recognised details use the
existing preview. Quotes and Keep in title still protect literal text.

While typing a date word, near-miss spellings such as `tommorow` or `wedensday` offer a suggestion in
the existing suggestion row. Tap to replace that word; nothing is corrected automatically. Suggestions
respect quoted/literal text and the cursor, and leave the rest of the sentence intact.

More everyday wording:

- `Remind me to call mum tomorrow` — a task called Call mum, reminded at 09:00. With a time it becomes an event
  reminded at that time. Choosing Task or Event yourself overrides the suggestion.
- `Call mum in 30 minutes` or `in an hour` — today, counted from now and rounded up to the next five minutes.
- `Meeting in 2 months`, `a year from today`
- `Dentist on the 5th`, `the 5th of October` — the next 5th on or after today.
- `Pay rent 1st of every month` — a monthly repeat starting on the next 1st.
- `Team sync every weekday 9am` — Monday to Friday; also `on weekdays` or just `weekdays`. A weekend start moves
  to Monday.
- `Tennis 7pm every Thursday for 10 weeks` — with a repeat, `for 10 weeks` (days, weeks, fortnights, months, years)
  sets how many times it repeats. Without a repeat it stays in the title: `Holiday for 2 weeks`.
- `Flight Friday 6am remind me the day before` (also `the week before`).
- `Dentist Friday 2 October 3pm` — the weekday is checked against the date; a mismatch asks you to correct it.
  `Fri 3/10` picks whichever reading is a Friday.
- `weds` and `thur` are recognised.

Four-digit 24-hour times work where they read as times: with a leading zero (`0600`, `0000`), after
`at`/`from`/`until` or a date (`tomorrow 1500`, `Friday 1930`), with `hrs`/`h` (`1800hrs`), or in a range
(`0900-1700`, `2200-0600`). Other four-digit numbers stay in the title: `Buy 1500 screws`, `Tax return 2027`,
`Meeting 1500` (write `at 1500` or `1500hrs`).

Times such as `7:30` ask Morning or afternoon; `07:30`, `19:30` and `7:30pm` do not. Numeric dates such as `3/4`
follow Settings → Date format when it is day-first or month-first (including the system setting); otherwise
Quick entry asks which date you meant.

`sun`, `sat`, `wed`, `noon` and `midnight` stay in the title when an ordinary word follows them: `Buy sun cream`,
`Sat nav`, `Midnight Mass`. Before a time, a date or words such as `at` and `with` they still schedule.
