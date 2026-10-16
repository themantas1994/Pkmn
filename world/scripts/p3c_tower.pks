# Part 3C: Battle Tower. A challenge is 7 scripted battles in the Battle Room with a full heal between rounds.
# Vars: p3c_tower_streak (consecutive wins, reset on a loss), p3c_tower_won (wins in the current set),
#       p3c_bp (Battle Points), p3c_tower_sets (sets cleared). Flags: p3c_tower_cleared, p3c_tower_tycoon.
# Note: a battle's result must be read right after `battle` (any message box resets `result`).

script p3c_tower_desk
  faceplayer
  say "Welcome to the Battle Tower! Here, trainers test their teams in seven consecutive battles."
  choice "What would you like to do?" "Take the challenge" "Hear the rules" "Cancel"
  if result 0
    if partysize < 3
      say "I'm sorry, you need at least three Pokémon to enter."
      return
    end
    say "Your current streak is {var:p3c_tower_streak}. Your Pokémon will be healed before each round.\pPlease, right this way!"
    call p3c_tower_run
  elif result 1
    say "Battle seven trainers in a row. Lose once and your streak resets to zero.\pEvery win earns 1 BP, clearing all seven earns 5 more. Spend BP at the counter on the right.\pRumor has it the Tower Tycoon appears for challengers on a 20-win streak..."
  else
    say "We look forward to your challenge!"
  end
end

script p3c_tower_run
  setvar p3c_tower_won 0
  heal
  warp battle_tower_room 4 4 up
  say "ROUND 1! Your opponent approaches!"
  call p3c_tower_tier_a
  if var p3c_tower_won < 1
    call p3c_tower_lost
    return
  end
  heal
  say "ROUND 2! Next opponent, step forward!"
  call p3c_tower_tier_a
  if var p3c_tower_won < 2
    call p3c_tower_lost
    return
  end
  heal
  say "ROUND 3!"
  call p3c_tower_tier_a
  if var p3c_tower_won < 3
    call p3c_tower_lost
    return
  end
  heal
  say "ROUND 4! The opponents only get tougher from here."
  call p3c_tower_tier_b
  if var p3c_tower_won < 4
    call p3c_tower_lost
    return
  end
  heal
  say "ROUND 5!"
  call p3c_tower_tier_b
  if var p3c_tower_won < 5
    call p3c_tower_lost
    return
  end
  heal
  say "ROUND 6!"
  call p3c_tower_tier_b
  if var p3c_tower_won < 6
    call p3c_tower_lost
    return
  end
  heal
  if var p3c_tower_streak == 20 or var p3c_tower_streak == 48
    say "ROUND 7! ...Wait. The lights are dimming. Someone is coming down from the top floor!"
    call p3c_tower_tycoon
  else
    say "ROUND 7! A Tower Ace stands before you!"
    call p3c_tower_tier_c
  end
  if var p3c_tower_won < 7
    call p3c_tower_lost
    return
  end
  call p3c_tower_clear
end

# Each round script runs one battle and records a win.
script p3c_tower_win
  addvar p3c_tower_won 1
  addvar p3c_tower_streak 1
  addvar p3c_bp 1
end

script p3c_tower_tier_a
  if random 6
    battle p3c_bt_a1 canlose
  elif random 5
    battle p3c_bt_a2 canlose
  elif random 4
    battle p3c_bt_a3 canlose
  elif random 3
    battle p3c_bt_a4 canlose
  elif random 2
    battle p3c_bt_a5 canlose
  else
    battle p3c_bt_a6 canlose
  end
  if result win
    call p3c_tower_win
  end
end

script p3c_tower_tier_b
  if random 6
    battle p3c_bt_b1 canlose
  elif random 5
    battle p3c_bt_b2 canlose
  elif random 4
    battle p3c_bt_b3 canlose
  elif random 3
    battle p3c_bt_b4 canlose
  elif random 2
    battle p3c_bt_b5 canlose
  else
    battle p3c_bt_b6 canlose
  end
  if result win
    call p3c_tower_win
  end
end

script p3c_tower_tier_c
  if random 3
    battle p3c_bt_c1 canlose
  elif random 2
    battle p3c_bt_c2 canlose
  else
    battle p3c_bt_c3 canlose
  end
  if result win
    call p3c_tower_win
  end
end

script p3c_tower_tycoon
  battle p3c_bt_tycoon canlose
  if result win
    call p3c_tower_win
    addvar p3c_bp 10
    if not flag p3c_tower_tycoon
      setflag p3c_tower_tycoon
      say "TYCOON AURELIO: Take this symbol of your triumph. The Tower will remember your name."
      giveitem abilitypatch
      giveitem goldbottlecap
      quest p3c_tower done
    end
  end
end

script p3c_tower_lost
  setvar p3c_tower_streak 0
  heal
  say "Your challenge has ended. Your streak was reset. Please come again!"
  warp battle_tower 5 5 down
end

script p3c_tower_clear
  addvar p3c_tower_sets 1
  addvar p3c_bp 5
  heal
  say "Congratulations! You've cleared all seven rounds! You earned a bonus of 5 BP.\pYou now have {var:p3c_bp} BP, and a streak of {var:p3c_tower_streak}."
  if not flag p3c_tower_cleared
    setflag p3c_tower_cleared
    say "For your first clear, please accept this commemorative prize."
    giveitem expcandyxl 2
    if not quest p3c_tower started
      quest p3c_tower start
    end
    quest p3c_tower 1
  end
  warp battle_tower 5 5 down
end

script p3c_tower_record
  say "BATTLE TOWER RECORD\pCurrent streak: {var:p3c_tower_streak} wins.\pSets cleared: {var:p3c_tower_sets}.\pBattle Points: {var:p3c_bp} BP."
end

# ---------------------------------------------------------------- BP exchange
script p3c_bp_exchange
  faceplayer
  say "Welcome to the BP Exchange! You have {var:p3c_bp} BP."
  choice "Which prizes?" "Training (mints, candy)" "Hold items" "Power items" "Rare prizes" "Cancel"
  if result 0
    call p3c_bp_training
  elif result 1
    call p3c_bp_hold
  elif result 2
    call p3c_bp_power
  elif result 3
    call p3c_bp_rare
  else
    say "Please come again!"
  end
end

script p3c_bp_nope
  say "I'm sorry, you don't have enough BP."
end

script p3c_bp_training
  choice "Training prizes" "Rare Candy x3 (6 BP)" "Bottle Cap (12 BP)" "Ability Capsule (16 BP)" "Nature mint (12 BP)" "PP Max (8 BP)" "Cancel"
  if result 0
    if var p3c_bp >= 6
      addvar p3c_bp -6
      giveitem rarecandy 3
    else
      call p3c_bp_nope
    end
  elif result 1
    if var p3c_bp >= 12
      addvar p3c_bp -12
      giveitem bottlecap
    else
      call p3c_bp_nope
    end
  elif result 2
    if var p3c_bp >= 16
      addvar p3c_bp -16
      giveitem abilitycapsule
    else
      call p3c_bp_nope
    end
  elif result 3
    call p3c_bp_mints
  elif result 4
    if var p3c_bp >= 8
      addvar p3c_bp -8
      giveitem ppmax
    else
      call p3c_bp_nope
    end
  end
end

script p3c_bp_mints
  if var p3c_bp < 12
    call p3c_bp_nope
    return
  end
  choice "Which mint? (12 BP)" "Adamant" "Modest" "Jolly" "Timid" "Bold" "Calm" "Cancel"
  if result 0
    addvar p3c_bp -12
    giveitem adamantmint
  elif result 1
    addvar p3c_bp -12
    giveitem modestmint
  elif result 2
    addvar p3c_bp -12
    giveitem jollymint
  elif result 3
    addvar p3c_bp -12
    giveitem timidmint
  elif result 4
    addvar p3c_bp -12
    giveitem boldmint
  elif result 5
    addvar p3c_bp -12
    giveitem calmmint
  end
end

script p3c_bp_hold
  if var p3c_bp < 24
    say "Hold items cost 24 BP each."
    call p3c_bp_nope
    return
  end
  choice "Hold items (24 BP)" "Leftovers" "Life Orb" "Focus Sash" "Assault Vest" "Choice Band" "Choice Specs" "Choice Scarf" "Cancel"
  if result 0
    addvar p3c_bp -24
    giveitem leftovers
  elif result 1
    addvar p3c_bp -24
    giveitem lifeorb
  elif result 2
    addvar p3c_bp -24
    giveitem focussash
  elif result 3
    addvar p3c_bp -24
    giveitem assaultvest
  elif result 4
    addvar p3c_bp -24
    giveitem choiceband
  elif result 5
    addvar p3c_bp -24
    giveitem choicespecs
  elif result 6
    addvar p3c_bp -24
    giveitem choicescarf
  end
end

script p3c_bp_power
  if var p3c_bp < 16
    say "Power items cost 16 BP each."
    call p3c_bp_nope
    return
  end
  choice "Power items (16 BP)" "Weakness Policy" "Rocky Helmet" "Heavy-Duty Boots" "Light Clay" "Air Balloon" "Macho Brace" "Cancel"
  if result 0
    addvar p3c_bp -16
    giveitem weaknesspolicy
  elif result 1
    addvar p3c_bp -16
    giveitem rockyhelmet
  elif result 2
    addvar p3c_bp -16
    giveitem heavydutyboots
  elif result 3
    addvar p3c_bp -16
    giveitem lightclay
  elif result 4
    addvar p3c_bp -16
    giveitem airballoon
  elif result 5
    addvar p3c_bp -16
    giveitem machobrace
  end
end

script p3c_bp_rare
  choice "Rare prizes" "Gold Bottle Cap (48 BP)" "Ability Patch (48 BP)" "Lucarionite (64 BP)" "Garchompite (64 BP)" "Metagrossite (64 BP)" "Salamencite (64 BP)" "Cancel"
  if result 0
    if var p3c_bp >= 48
      addvar p3c_bp -48
      giveitem goldbottlecap
    else
      call p3c_bp_nope
    end
  elif result 1
    if var p3c_bp >= 48
      addvar p3c_bp -48
      giveitem abilitypatch
    else
      call p3c_bp_nope
    end
  elif result 2
    if var p3c_bp >= 64
      addvar p3c_bp -64
      giveitem lucarionite
    else
      call p3c_bp_nope
    end
  elif result 3
    if var p3c_bp >= 64
      addvar p3c_bp -64
      giveitem garchompite
    else
      call p3c_bp_nope
    end
  elif result 4
    if var p3c_bp >= 64
      addvar p3c_bp -64
      giveitem metagrossite
    else
      call p3c_bp_nope
    end
  elif result 5
    if var p3c_bp >= 64
      addvar p3c_bp -64
      giveitem salamencite
    else
      call p3c_bp_nope
    end
  end
end
