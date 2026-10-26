# Side-quest chain C: "The Umbra Files" (PKM-76, design PKM-51). One quest (p5c_umbra_files), ten steps.
# Step N (0-7) = document N+1; step 8 = epilogue with Rook; step 9 = file everything with the archivist.
# Nothing here is on the critical path; rewards are items, a gift Egg and lore, no EXP beyond three level-matched rematches.

# ---------------------------------------------------------------- Document texts (read on pickup, replayed in the archive)
script p5c_doc_1
  say "DOCUMENT 1, Rook's notes: \"Umbra started Team Eclipse after the Great Heat. One summer every field around Tessera dried to dust.\pNobody blamed the sun. So Umbra decided to.\""
end

script p5c_doc_2
  say "DOCUMENT 2, Corvin's file: \"Corvin's family orchard burned in the Great Heat. Only the poison swamp stayed green.\pHe stopped trusting anything that shines.\""
end

script p5c_doc_3
  say "DOCUMENT 3, Vesper's visions: \"One hundred and thirteen visions of the future. One hundred and twelve end in fire.\pVesper never wrote down the hundred and thirteenth.\""
end

script p5c_doc_4
  say "DOCUMENT 4, the lens plan: \"The Prism stores the sun's light in its crystals. A lens could drink it, and the sky would dim to a gentle, endless dusk.\pNo more glare. No more scorched fields.\""
end

script p5c_doc_5
  say "DOCUMENT 5, test report: \"The first test beam burned a hole through the caldera rim.\pUmbra wrote in the margin: 'Light is not a gift. It is a weapon nobody has aimed yet.'\""
end

script p5c_doc_6
  say "DOCUMENT 6, field survey: \"The Frozen Falls shrink a little every summer. Umbra's measurements start in the Great Heat.\pThe eclipse was never about hate. It was about slowing the thaw.\""
end

script p5c_doc_7
  say "DOCUMENT 7, last entry: \"If a child beats me at the summit, let the light stand.\pBut I will not apologize to the sun.\""
end

script p5c_doc_8
  say "DOCUMENT 8, Umbra's letter: \"To Celeste, who chose to stand under the light while I chose to hide from it.\pYou were right that it is not a thief. I only wanted it to stop hurting.\""
end

# Shared hint for a document spot whose step is not open (quest not started, or an earlier document is still missing).
script p5c_not_yet
  if quest p5c_umbra_files done
    say "Nothing here now. The document is filed in the hideout archive."
  elif quest p5c_umbra_files started
    say "Nothing here yet. Your quest log lists the documents in order."
  else
    say "Just scenery. You have no reason to search here."
  end
end

# ---------------------------------------------------------------- The defector (Whisperwood) and his hideout
script p5c_defector
  faceplayer
  if quest p5c_umbra_files started or quest p5c_umbra_files done
    say "ROOK: Go inside. The hideout is the gap in the trees by the shrine."
  elif field cut or field rock_smash
    say "ROOK: Shh! Don't look at me. I used to wear the Eclipse uniform.\pI left after the docks. Umbra is not a monster, and nobody will believe that unless somebody writes it down.\pI hid eight documents across the region. Will you collect them for me?"
    ask "Help the defector?"
    if result yes
      say "ROOK: Thank you. The first page is on the desk in my hideout, the gap in the trees by the shrine."
      quest p5c_umbra_files start
      setflag p5c_defector_in
      hide p5c_defector
    else
      say "ROOK: I understand. I'll be here if you change your mind."
    end
  else
    say "ROOK: Psst! The thicket around my hideout is too dense for me.\pCome back once you can clear brush (Cut or Rock Smash)."
  end
end

script p5c_desk
  if quest p5c_umbra_files step == 0
    call p5c_doc_1
    quest p5c_umbra_files next
    say "Next: Document 2, a crate in the Harbor Warehouse in Port Tidewater."
  else
    call p5c_not_yet
  end
end

script p5c_rook
  faceplayer
  if quest p5c_umbra_files done
    say "ROOK: The archive is safe. I run the shop now. Prices are fair. Mostly."
  elif quest p5c_umbra_files step == 8
    say "ROOK: Umbra's letter? Please, read it to me."
    fadeout
    say "UMBRA: Celeste, I watched you leave the lab with a lantern and a smile.\pYou said the light only hurts when you hide from it."
    say "CELESTE: And you said the sky would forgive us if we stopped asking it to burn.\pWe were both wrong, Umbra. And both right."
    say "UMBRA: Then let the child decide. Light and shadow, side by side.\pLet the eclipse pass."
    fadein
    say "ROOK: ...The eclipse was never the point. It was grief.\pI'm done hiding. Thank you, {player}. Take the pages to the archivist."
    setflag p5c_epilogue
    quest p5c_umbra_files next
  elif quest p5c_umbra_files started
    say "ROOK: Every document is another piece of why. Check your quest log for the next one."
  else
    say "ROOK: Just a clearing, nothing to see. Move along."
  end
end

script p5c_archivist
  faceplayer
  if quest p5c_umbra_files done
    ask "ARCHIVIST: Would you like to read the Umbra Files again?"
    if result yes
      call p5c_archive_read
    end
  elif quest p5c_umbra_files step == 9
    say "ARCHIVIST: Eight documents and a letter! The Archive of Tessera thanks you.\pThis Egg came with Umbra's belongings. The note says 'for whoever finishes the story.'"
    giveegg sneasel 1
    if result 1
      tm foulplay
      giveitem blackglasses 1
      giveitem darkterashard 1
      setflag p5c_files_filed
      quest p5c_umbra_files done
      say "ARCHIVIST: Foul Play, Black Glasses and a Dark Tera Shard are yours too. Rook's shop is open now."
    else
      say "ARCHIVIST: Your party and your boxes are full! Make room for the Egg first, then come back."
    end
  elif quest p5c_umbra_files started
    say "ARCHIVIST: I'm cataloguing the Umbra Files. Bring me all eight documents and Umbra's letter."
  else
    say "ARCHIVIST: This is a private archive. Rook says to wait for his friend."
  end
end

script p5c_archive_read
  choice "Which pages?" "Documents 1 to 4" "Documents 5 to 8" "Leave"
  if result 0
    choice "Which document?" "1: Rook" "2: Corvin" "3: Vesper" "4: The lens"
    if result 0
      call p5c_doc_1
    elif result 1
      call p5c_doc_2
    elif result 2
      call p5c_doc_3
    elif result 3
      call p5c_doc_4
    end
  elif result 1
    choice "Which document?" "5: The test" "6: The falls" "7: Last entry" "8: The letter"
    if result 0
      call p5c_doc_5
    elif result 1
      call p5c_doc_6
    elif result 2
      call p5c_doc_7
    elif result 3
      call p5c_doc_8
    end
  end
end

script p5c_vendor
  faceplayer
  if quest p5c_umbra_files done
    say "EX-GRUNT: Welcome to the ex-Eclipse surplus. Everything is legal. Mostly."
    shop p5c_ex_eclipse
  else
    say "EX-GRUNT: The shop isn't open yet. Rook says to finish the Umbra Files first."
  end
end

# ---------------------------------------------------------------- Document 2: Harbor Warehouse (two switches)
script p5c_wh_switch_a
  if quest p5c_umbra_files step == 1 and not flag p5c_wh_a
    setflag p5c_wh_a
    say "Click! A lock behind the crates releases."
  elif quest p5c_umbra_files step == 1
    say "The switch is already down."
  else
    call p5c_not_yet
  end
end

script p5c_wh_switch_b
  if quest p5c_umbra_files step == 1 and not flag p5c_wh_b
    setflag p5c_wh_b
    say "Click! A second lock releases."
  elif quest p5c_umbra_files step == 1
    say "The switch is already down."
  else
    call p5c_not_yet
  end
end

script p5c_wh_crate
  if quest p5c_umbra_files step == 1
    if flag p5c_wh_a and flag p5c_wh_b
      say "Both locks are open. A folder is hidden under the packing straw."
      call p5c_doc_2
      quest p5c_umbra_files next
      say "Next: Document 3, the Voltara Power Plant."
    else
      say "A sturdy crate with two locks. There must be two switches somewhere in the warehouse."
    end
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 3: Voltara Power Plant (key-card + safe)
script p5c_pp_guard
  faceplayer
  if quest p5c_umbra_files step == 2
    say "GUARD: The Eclipse grunts left a safe in here. The key-card is in one of the lockers.\pI heard it rattle in the middle one, near the loud generator."
  else
    say "GUARD: The grid is back up. Don't touch the cables."
  end
end

script p5c_pp_locker_a
  if quest p5c_umbra_files step == 2
    say "Empty. Someone left a damp sock."
  else
    call p5c_not_yet
  end
end

script p5c_pp_locker_c
  if quest p5c_umbra_files step == 2
    say "Empty. A spare hard hat."
  else
    call p5c_not_yet
  end
end

script p5c_pp_locker_b
  if quest p5c_umbra_files step == 2
    if flag p5c_keycard
      say "Empty now. You already took the key-card."
    else
      setflag p5c_keycard
      say "A key-card is taped inside the door! You pocket it."
    end
  else
    call p5c_not_yet
  end
end

script p5c_pp_safe
  if quest p5c_umbra_files step == 2
    if flag p5c_keycard
      say "The key-card unlocks the safe. Inside is a folder."
      call p5c_doc_3
      quest p5c_umbra_files next
      say "Next: Document 4, the boulder room on Mt. Prism 2F."
    else
      say "A wall safe with a card reader. You need a key-card."
    end
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 4: Mt. Prism 2F (Strength boulder room)
script p5c_mp2_page
  if quest p5c_umbra_files step == 3
    say "A page is wedged behind a rock in the boulder room."
    call p5c_doc_4
    quest p5c_umbra_files next
    say "Next: Document 5, the heat vent in Ashfall Caldera. Bring a fire-resistant Pokémon."
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 5: Ashfall Caldera (heat gate)
script p5c_cal_vent
  if quest p5c_umbra_files step == 4
    if party has cyndaquil or party has quilava or party has typhlosion or party has charmander or party has charmeleon or party has charizard or party has vulpix or party has ninetales or party has growlithe or party has arcanine or party has ponyta or party has rapidash or party has magmar or party has magmortar or party has flareon or party has slugma or party has magcargo or party has torkoal or party has houndoom or party has totodile or party has croconaw or party has feraligatr or party has squirtle or party has wartortle or party has blastoise or party has vaporeon or party has lapras or party has gyarados
      say "Scorching steam pours from the vent. Your Pokémon shrugs it off and lifts a heat-warped case out of the crack."
      call p5c_doc_5
      quest p5c_umbra_files next
      say "Next: Document 6, above the frozen waterfall in Frozen Falls on Route 12."
    else
      say "Scorching steam pours from the vent. A case glints inside, but your team would be cooked.\pBring a Fire or Water Pokémon, or another one that shrugs off heat."
    end
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 6: Frozen Falls (Waterfall)
script p5c_ff_page
  if quest p5c_umbra_files step == 5
    say "A waterproof tube is frozen into the ledge above the falls."
    call p5c_doc_6
    quest p5c_umbra_files next
    say "Next: Document 7, the Prism Summit peak. Beat Umbra first."
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 7: Prism Summit peak (post-Umbra)
script p5c_pk_page
  if quest p5c_umbra_files step == 6
    say "A journal lies where Umbra stood, open to the last entry."
    call p5c_doc_7
    setflag p5c_gauntlet_open
    quest p5c_umbra_files next
    say "Next: Document 8, Umbra's letter in the Eclipse Lens Lab. Three ex-admins are waiting there."
  else
    call p5c_not_yet
  end
end

# ---------------------------------------------------------------- Document 8: Eclipse Lens Lab (ex-admin gauntlet)
script p5c_lab_vesper
  faceplayer
  if quest p5c_umbra_files step == 7
    if flag p5c_g_vesper
      say "VESPER: You beat me. Corvin is next."
    else
      battle p5c_ex_vesper canlose
      if result win
        setflag p5c_g_vesper
        say "VESPER: Go on. Corvin is next."
      else
        say "VESPER: I saw this future too. Heal up and try again."
      end
    end
  else
    say "VESPER: The future is quiet now. How dull."
  end
end

script p5c_lab_corvin
  faceplayer
  if quest p5c_umbra_files step == 7
    if not flag p5c_g_vesper
      say "CORVIN: Vesper first. Rules are rules."
    elif flag p5c_g_corvin
      say "CORVIN: Halloran is next. Don't break anything."
    else
      battle p5c_ex_corvin canlose
      if result win
        setflag p5c_g_corvin
        say "CORVIN: Halloran is next. Don't break anything."
      else
        say "CORVIN: Hmph. Heal up and try again."
      end
    end
  else
    say "CORVIN: I'm making orchard plans. Don't ask."
  end
end

script p5c_lab_halloran
  faceplayer
  if quest p5c_umbra_files step == 7
    if not flag p5c_g_corvin
      say "HALLORAN: Corvin first! I need time to polish my team."
    elif flag p5c_g_halloran
      say "HALLORAN: The letter is on the desk. Take it."
    else
      battle p5c_ex_halloran canlose
      if result win
        setflag p5c_g_halloran
        say "HALLORAN: The letter is on the desk. Take it."
      else
        say "HALLORAN: My lens cloth wins today! Heal up and try again."
      end
    end
  else
    say "HALLORAN: Back to polishing."
  end
end

script p5c_lab_letter
  if quest p5c_umbra_files step == 7
    if flag p5c_g_halloran
      say "An envelope in Umbra's handwriting."
      call p5c_doc_8
      quest p5c_umbra_files next
      say "Take the letter to Rook in the Whisperwood hideout."
    else
      say "Umbra's letter lies on the desk, but the ex-admins stand between you and it."
    end
  else
    call p5c_not_yet
  end
end
