# Part 3B: Route 13, Highland Lodge, Victory Road and the final rival battle.

script p3b_vr_gate
  face r13_officer up
  if badges >= 8
    setflag p3b_vr_checked
    say "OFFICER: Badge check! One, two... eight! All genuine. Victory Road is yours. Good luck!"
  else
    say "OFFICER: Halt! Only trainers with all eight Badges may enter Victory Road."
    move player left
  end
end

script p3b_vr_officer
  faceplayer
  say "OFFICER: Victory Road climbs three floors through the mountain. You'll need Waterfall and Strength to reach the top."
end

script p3b_lodge_keeper
  faceplayer
  say "KEEPER: Welcome to the Highland Lodge, dear. Sit, sit. Have some tea while your Pokémon rest."
  heal
  say "KEEPER: There. Everyone's warm and rested. Victory Road can be cold, so come back any time."
end

script p3b_lodge_veteran
  faceplayer
  if flag p3b_lodge_gift
    say "VETERAN: An Earthquake shakes even Steel-types. Make good use of it."
  else
    say "VETERAN: I reached the Champion once, a long time ago. Lost on the very last turn.\pYou have the look of someone who won't. Take this. It carried me far."
    tm earthquake
    setflag p3b_lodge_gift
  end
end

script p3b_vr1_hiker
  faceplayer
  if field waterfall
    say "HIKER: See that waterfall? With your Badges and a strong swimmer, you can climb right up it!"
  else
    say "HIKER: The way up is that big waterfall. You need the Frost Badge and a Pokémon that can learn Waterfall.\pSeaking, Gyarados, Lapras... plenty of them can. There's a ledge on the east side if you need to come back down."
  end
end

script p3b_vr_rival_talk
  faceplayer
  say "{rival}: Hey! Come over here a sec!"
end

script p3b_vr_rival
  face vr3_rival right
  playerface left
  say "{rival}: There you are. I've been waiting.\pFrom Hollowbrook to the summit, you've been one step ahead the whole way. So here's the deal: one last battle. Right here, before the League."
  if flag starter_grass
    battle p3b_rival_vr_typhlosion canlose
  elif flag starter_fire
    battle p3b_rival_vr_feraligatr canlose
  else
    battle p3b_rival_vr_meganium canlose
  end
  if result win
    say "{rival}: ...Yeah. I knew it. You're the real deal, {player}. You always were.\pGo out there and win it all. For both of us."
  else
    say "{rival}: I... won? I actually won!\pDon't you dare lose up there, okay? I want to say I beat the Champion once."
  end
  heal
  say "{rival}: The plateau's right through there. I'll be cheering, even if I'm too far away to hear. See you on the other side!"
  setflag p3b_rival_vr_done
  quest p3b_story next
  fadeout
  hide vr3_rival
  fadein
end
