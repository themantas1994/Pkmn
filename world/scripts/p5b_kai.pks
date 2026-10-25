# Side-quest chain B: "Kai's Notebook" (PKM-77, design PKM-51). One quest (p5b_kai_notebook), seven steps (0-6); the capstone ends with `quest done`.
# Step 0 cache (Route 3), 1 judges (Port Tidewater), 2 terminals (Voltara lab), 3 rails (Quarrytown), 4 spar (Seraphine), 5 apology (Frostmere), 6 capstone (Victory Road).
# Nothing here is on the critical path; rewards are items, TMs and lore. The spar and capstone are level-capped (see world/trainers/p5b.txt).

# Shared hint for an NPC or spot whose step is not open.
script p5b_not_yet
  if quest p5b_kai_notebook done
    say "Nothing more here. Kai's notebook is finished."
  elif quest p5b_kai_notebook started
    say "Nothing here yet. Your quest log lists the entries in order."
  else
    say "Just scenery. You have no reason to look closer."
  end
end

# ---------------------------------------------------------------- Entry 1: the page (Brindle City) and the cache (Route 3)
script p5b_page
  if quest p5b_kai_notebook done or quest p5b_kai_notebook started
    say "The bench is empty now. You already have {rival}'s page."
  elif badge 1
    say "A torn page from a notebook is wedged under the bench. The handwriting is {rival}'s.\p\"ENTRY 1. Hazel made me work for that Badge. Fine. I'm hiding my spare supplies on Route 3, far west, behind the first tree you need Cut for.\pIf you're reading this, you're following me. Good.\""
    quest p5b_kai_notebook start
    say "Your quest log now has Kai's Notebook."
  else
    say "A scrap of notebook paper is pinned under a stone. The ink is smudged. Maybe it will make sense after the gym."
  end
end

script p5b_cache
  if quest p5b_kai_notebook step == 0
    say "A small cache, sealed with a doodle of a flame. Inside is a page and a PP Max.\p\"ENTRY 2. Port Tidewater has a cooking contest. Three judges, each wants a certain kind of Pokémon. I couldn't win it. Maybe you can.\""
    giveitem ppmax 1
    setflag p5b_cache_taken
    quest p5b_kai_notebook next
    hide p5b_cache
  else
    say "A cache sealed with a doodle of a flame. It is not for you yet."
  end
end

# ---------------------------------------------------------------- Entry 2: the judges (Port Tidewater, badge 2)
script p5b_judges_check
  if flag p5b_j1 and flag p5b_j2 and flag p5b_j3
    say "The judges huddle, then ring the bell. \"Three out of three! The cook with the best-matched team wins.\"\pThey hand you a Focus Sash and a page from {rival}'s notebook.\p\"ENTRY 3. Mira's lab in Voltara keeps glitching. The terminals only come back in the right order. The log terminal lists it.\""
    giveitem focussash 1
    setflag p5b_judges_done
    quest p5b_kai_notebook next
  end
end

script p5b_judge_idle
  if quest p5b_kai_notebook done or flag p5b_judges_done
    say "JUDGE: The contest is over. Thank you for the show!"
  elif quest p5b_kai_notebook step == 1
    say "JUDGE: Judging begins once you have two Badges. Come back then."
  else
    say "JUDGE: A cooking contest is planned here. Not today."
  end
end

script p5b_judge_1
  faceplayer
  if quest p5b_kai_notebook step == 1 and badges >= 2
    if flag p5b_j1
      say "JUDGE: You already impressed me with your water-loving Pokémon."
    elif party has squirtle or party has wartortle or party has blastoise or party has totodile or party has croconaw or party has feraligatr or party has vaporeon or party has lapras or party has gyarados or party has psyduck or party has golduck or party has poliwag or party has poliwhirl or party has poliwrath or party has horsea or party has seadra or party has staryu or party has starmie
      say "JUDGE: A Pokémon that loves the water! A fine start. Point one."
      setflag p5b_j1
      call p5b_judges_check
    else
      say "JUDGE: For my taste, show me a Pokémon that loves the water."
    end
  else
    call p5b_judge_idle
  end
end

script p5b_judge_2
  faceplayer
  if quest p5b_kai_notebook step == 1 and badges >= 2
    if flag p5b_j2
      say "JUDGE: Your crackling Pokémon already won me over."
    elif party has pikachu or party has raichu or party has jolteon or party has electabuzz or party has magneton or party has magnezone or party has ampharos or party has mareep or party has flaaffy or party has voltorb or party has electrode or party has elekid or party has manectric or party has luxray
      say "JUDGE: Static in the air! Delicious. Point two."
      setflag p5b_j2
      call p5b_judges_check
    else
      say "JUDGE: Show me a Pokémon that crackles with electricity."
    end
  else
    call p5b_judge_idle
  end
end

script p5b_judge_3
  faceplayer
  if quest p5b_kai_notebook step == 1 and badges >= 2
    if flag p5b_j3
      say "JUDGE: Your leafy partner has my vote."
    elif party has chikorita or party has bayleef or party has meganium or party has bulbasaur or party has ivysaur or party has venusaur or party has oddish or party has gloom or party has vileplume or party has bellsprout or party has weepinbell or party has victreebel or party has exeggcute or party has exeggutor or party has tangela
      say "JUDGE: Fresh leaves! Point three."
      setflag p5b_j3
      call p5b_judges_check
    else
      say "JUDGE: Show me a Pokémon that grows leaves."
    end
  else
    call p5b_judge_idle
  end
end

# ---------------------------------------------------------------- Entry 3: the glitching terminals (Voltara lab, badge 3)
# Correct order: B, then A, then C. Each terminal checks p5b_seq; a wrong press resets it.
script p5b_term_press_ok
  addvar p5b_seq 1
  if var p5b_seq >= 3
    say "The terminals chime together. The glitch is gone, and a printout slides out.\p\"ENTRY 4. Quarrytown's mine carts ran on their own for a week. Reset the three rails in the order the carts run, and ask the foreman.\"\pA TM was taped under the printer."
    tm voltswitch
    setflag p5b_terms_done
    quest p5b_kai_notebook next
  else
    say "The terminal beeps. One more to go in the sequence."
  end
end

script p5b_term_bad
  setvar p5b_seq 0
  say "ERROR. The sequence resets. Check the log terminal."
end

script p5b_term_idle
  if quest p5b_kai_notebook done or flag p5b_terms_done
    say "The terminal runs clean. No glitches."
  elif quest p5b_kai_notebook step == 2
    say "The screen flickers. You need three Badges before the lab lets you touch it."
  else
    say "A lab terminal, flickering. It is not your business yet."
  end
end

script p5b_term_a
  if quest p5b_kai_notebook step == 2 and badges >= 3
    if var p5b_seq == 1
      call p5b_term_press_ok
    else
      call p5b_term_bad
    end
  else
    call p5b_term_idle
  end
end

script p5b_term_b
  if quest p5b_kai_notebook step == 2 and badges >= 3
    if var p5b_seq == 0
      call p5b_term_press_ok
    else
      call p5b_term_bad
    end
  else
    call p5b_term_idle
  end
end

script p5b_term_c
  if quest p5b_kai_notebook step == 2 and badges >= 3
    if var p5b_seq == 2
      call p5b_term_press_ok
    else
      call p5b_term_bad
    end
  else
    call p5b_term_idle
  end
end

script p5b_term_log
  if quest p5b_kai_notebook step == 2 and badges >= 3
    say "GLITCH LOG: The sequence that stabilises the lab is\n  B (east wall), then A (west wall), then C (south, by the door)."
  else
    call p5b_term_idle
  end
end

# ---------------------------------------------------------------- Entry 4: the mine-cart rails (Quarrytown, badge 4)
# Correct order: rail 1 (north-west), rail 2 (east), rail 3 (south-west sand).
script p5b_rail_press_ok
  addvar p5b_rails 1
  if var p5b_rails >= 3
    say "Click. The last rail locks in. Far off, the carts roll the right way.\pThe foreman will want to hear about this."
    setflag p5b_rails_done
  else
    say "Click. The rail locks in. Next one."
  end
end

script p5b_rail_bad
  setvar p5b_rails 0
  say "CLANG! The rails jump back to the start. The carts run in a loop: north-west, east, south-west."
end

script p5b_rail_idle
  say "A rail lever. It is stiff and rusty."
end

script p5b_rail_1
  if quest p5b_kai_notebook step == 3 and badges >= 4 and not flag p5b_rails_done
    if var p5b_rails == 0
      call p5b_rail_press_ok
    else
      call p5b_rail_bad
    end
  else
    call p5b_rail_idle
  end
end

script p5b_rail_2
  if quest p5b_kai_notebook step == 3 and badges >= 4 and not flag p5b_rails_done
    if var p5b_rails == 1
      call p5b_rail_press_ok
    else
      call p5b_rail_bad
    end
  else
    call p5b_rail_idle
  end
end

script p5b_rail_3
  if quest p5b_kai_notebook step == 3 and badges >= 4 and not flag p5b_rails_done
    if var p5b_rails == 2
      call p5b_rail_press_ok
    else
      call p5b_rail_bad
    end
  else
    call p5b_rail_idle
  end
end

script p5b_qt_foreman
  faceplayer
  if quest p5b_kai_notebook step == 3 and badges >= 4
    if flag p5b_rails_done
      say "FOREMAN: The carts are back on schedule! A kid with a notebook told me you'd come. Here, a TM for your trouble.\pHe left a page for you.\p\"ENTRY 5. Seraphine Town. You said winning is about reading the field. Prove it in an honest spar, no tricks.\""
      tm rockslide
      quest p5b_kai_notebook next
    else
      say "FOREMAN: The carts keep running wild. Reset the three rails in the order they run: north-west lever, east lever, south-west lever on the sand."
    end
  elif quest p5b_kai_notebook step == 3
    say "FOREMAN: The carts are a mess, but it takes four Badges to be allowed in the shafts."
  elif quest p5b_kai_notebook done or flag p5b_rails_done
    say "FOREMAN: The carts are running fine. Thanks!"
  else
    say "FOREMAN: Mind the carts. They go where they please."
  end
end

# ---------------------------------------------------------------- Entry 5: the honest spar (Seraphine Town, badge 5)
script p5b_st_kai
  faceplayer
  if quest p5b_kai_notebook step == 4 and badges >= 5
    say "{rival}: There you are. Entry 5: an honest spar. No Mega, no Tera, no gimmicks, just us and the field.\pWatch where each of us stands. Ready?"
    if flag starter_grass
      battle p5b_spar_typhlosion canlose
    elif flag starter_fire
      battle p5b_spar_feraligatr canlose
    else
      battle p5b_spar_meganium canlose
    end
    if result win
      say "{rival}: Heh. You read the field better than I do.\pTake this. It's the Choice Scarf I never learned to use properly.\p\"ENTRY 6. I owe you an apology. I'll wait for you in Frostmere Town.\""
      giveitem choicescarf 1
      heal
      quest p5b_kai_notebook next
    else
      say "{rival}: Not yet! Heal up and come back. I'll be right here."
      heal
    end
  elif quest p5b_kai_notebook step == 4
    say "{rival}: Come back when you have five Badges. An honest spar needs an honest challenge."
  elif quest p5b_kai_notebook done
    say "{rival}: That spar in the mist was the day I started respecting you."
  else
    say "{rival}: I'm just resting. Move along."
  end
end

# ---------------------------------------------------------------- Entry 6: the apology (Frostmere Town, badge 7)
script p5b_fm_kai
  faceplayer
  if quest p5b_kai_notebook step == 5 and badges >= 7
    say "{rival}: I'm sorry. For the way I talked to you in Hollowbrook, for all of it. I was scared of losing to you.\pHere. My first Poké Ball, the one I caught my starter with. I want you to keep it."
    giveitem luxuryball 1
    say "{rival}: One more page. \"CAPSTONE. Victory Road, the ladder on 2F in the east chamber. Come and finish it.\""
    setflag p5b_apology
    quest p5b_kai_notebook next
  elif quest p5b_kai_notebook step == 5
    say "{rival}: Not yet. I'll wait here until you have seven Badges."
  elif quest p5b_kai_notebook done or flag p5b_apology
    say "{rival}: The Ball is yours. I only asked you to hold on to it."
  else
    say "{rival}: I'm just watching the snow. Move along."
  end
end

# ---------------------------------------------------------------- Capstone (Victory Road side room, badge 8)
script p5b_kai_cap
  faceplayer
  if quest p5b_kai_notebook step == 6 and badges >= 8 and not quest p5b_kai_notebook done
    say "{rival}: You came. Last page, then.\pI'm not holding back, and neither should you. Tera allowed."
    if flag starter_grass
      battle p5b_cap_typhlosion canlose
    elif flag starter_fire
      battle p5b_cap_feraligatr canlose
    else
      battle p5b_cap_meganium canlose
    end
    if result win
      say "{rival}: ...Yeah. That's the page I was missing.\pHere, a TM and some PP Max. And the notebook is yours. It's all of it now."
      tm earthpower
      giveitem ppmax 2
      heal
      setflag p5b_kai_done
      quest p5b_kai_notebook done
    else
      say "{rival}: Almost! Heal up. I'll wait."
      heal
    end
  elif quest p5b_kai_notebook step == 6 and not quest p5b_kai_notebook done
    say "{rival}: Eight Badges first. Then we finish this."
  elif quest p5b_kai_notebook done
    if random 3
      say "{rival}: The notebook is full, but I keep writing in the margins."
    elif random 2
      say "{rival}: You'll beat the League. I'll be cheering, as loud as I can."
    else
      say "{rival}: Whenever you want a rematch, you know where to find me."
    end
  else
    say "{rival}: Not now. I'm thinking."
  end
end
