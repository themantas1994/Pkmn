# Professor Linden's lab: choosing a starter and the first rival battle.

# Lab intro flow (state is kept in flags, so a save/load at any point resumes correctly):
#   not got_starter, not lab_intro_done : lab_enter walks the player to the Professor and plays the intro
#   not got_starter, lab_intro_done     : free roaming; the exit row triggers lab_need_starter
#   got_starter, not got_pokedex        : the starter script (or lab_enter after a reload) runs lab_handoff
#   got_pokedex                         : done; the rival challenge trigger fires once on the way out

# Runs every time the lab is entered (also when a saved game is loaded inside it).
script lab_enter
  if not flag got_starter
    if not flag lab_intro_done
      call lab_intro
    elif playery >= 8
      # Came back in through the door without a starter: back to the Professor.
      walkto player 4 3
      playerface up
      face professor down
      say "PROF. LINDEN: {player}, please choose one of the three Pokémon on the table first."
    end
  elif not flag got_pokedex
    call lab_handoff
  end
  call p5a_mail
end

script lab_intro
  walkto player 4 3
  playerface up
  face professor down
  say "PROF. LINDEN: Ah, {player}! Right on time.\pI study how Pokémon and people live together across the Tessera region."
  say "PROF. LINDEN: I'd like you to help me complete the Pokédex.\pPlease choose one of the three Pokémon on the table."
  quest main_story start
  setflag lab_intro_done
end

# Walks the player back to the Professor and runs the hand-off conversation.
script lab_handoff
  walkto player 4 3
  playerface up
  face professor down
  call lab_handoff_talk
end

script lab_handoff_talk
  setflag got_pokedex
  say "PROF. LINDEN: Take good care of it! And take this, too."
  pokedex
  say "{player} received the Pokédex!"
  giveitem pokeball 5
  say "PROF. LINDEN: Throw a Poké Ball at a weakened wild Pokémon to catch it.\pHead north along Route 1 when you're ready!"
  quest main_story next
end

# Step trigger on the exit row while no starter has been chosen.
script lab_need_starter
  say "PROF. LINDEN: Wait, {player}! Please choose one of the Pokémon on the table before you leave."
  walkto player 4 7
end

script lab_professor
  faceplayer
  if not flag got_starter
    say "PROF. LINDEN: Please choose one of the three Pokémon on the table, {player}."
  elif not flag got_pokedex
    call lab_handoff_talk
  elif flag p5a_researchers_pin
    call p5a_professor_thanks
  else
    say "PROF. LINDEN: How is the Pokédex coming along? Every Pokémon you meet is recorded automatically."
  end
end

script lab_aide
  faceplayer
  if flag got_pokedex
    say "AIDE: My colleague is waiting in Fernwick Town with something useful for you.\pHe's the one in the lab coat. You can't miss him!"
  else
    say "AIDE: The Professor's Pokémon are very rare. Treat yours well!"
  end
end

script lab_rival
  faceplayer
  if not flag got_starter
    say "{rival}: Took you long enough, {player}! The Professor said I could pick after you.\pHurry up and choose!"
  else
    say "{rival}: Mine is way cooler than yours."
  end
end

script lab_ball_grass
  if flag got_starter
    say "That's the Professor's last Pokémon."
  else
    cry chikorita
    ask "PROF. LINDEN: Chikorita, the Leaf Pokémon. Will you take it?"
    if result yes
      givemon chikorita 5
      if result yes
        setflag got_starter
        setflag starter_grass
        setflag took_ball_grass
        hide ball_grass
        say "{rival}: Then I'll take this one!"
        setflag took_ball_fire
        hide ball_fire
        call lab_handoff
      end
    end
  end
end

script lab_ball_fire
  if flag got_starter
    say "That's the Professor's last Pokémon."
  else
    cry cyndaquil
    ask "PROF. LINDEN: Cyndaquil, the Fire Mouse Pokémon. Will you take it?"
    if result yes
      givemon cyndaquil 5
      if result yes
        setflag got_starter
        setflag starter_fire
        setflag took_ball_fire
        hide ball_fire
        say "{rival}: Then I'll take this one!"
        setflag took_ball_water
        hide ball_water
        call lab_handoff
      end
    end
  end
end

script lab_ball_water
  if flag got_starter
    say "That's the Professor's last Pokémon."
  else
    cry totodile
    ask "PROF. LINDEN: Totodile, the Big Jaw Pokémon. Will you take it?"
    if result yes
      givemon totodile 5
      if result yes
        setflag got_starter
        setflag starter_water
        setflag took_ball_water
        hide ball_water
        say "{rival}: Then I'll take this one!"
        setflag took_ball_grass
        hide ball_grass
        call lab_handoff
      end
    end
  end
end

script lab_rival_challenge
  face rival down
  say "{rival}: Hold it, {player}! Let's see whose Pokémon is stronger!"
  if flag starter_grass
    battle rival_lab_cyndaquil canlose
  elif flag starter_fire
    battle rival_lab_totodile canlose
  else
    battle rival_lab_chikorita canlose
  end
  if result win
    say "{rival}: Hmph. I'll train harder and beat you next time!"
  else
    say "{rival}: Ha! Told you mine was cooler!"
  end
  heal
  setflag rival_battled
  move rival down2
  hide rival
  setflag rival_left_lab
end
