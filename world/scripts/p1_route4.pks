# Route 4 and Cobblecrest Cave (Part 1): Rock Smash gift, lost hiker side quest.

script p1_r4_hiker
  faceplayer
  if quest p1_cave_hiker done
    say "Ol' Dudley made it home safe. Thanks again, kid!\pRemember: Rock Smash works outside of battle once you have the Tide Badge."
  elif flag p1_found_hiker
    say "You found Dudley? He's on his way out? Oh, thank goodness!"
    say "Here, take this. I dug it up on the mountain years ago."
    giveitem nugget
    quest p1_cave_hiker done
  elif flag p1_got_rocksmash
    say "Dudley went down the ladder on the east side of the cave.\pIf you see a hiker down there, tell him I'm worried sick!"
  else
    say "Heading into Cobblecrest Cave? Then take this TM. Every hiker should have one!"
    tm rocksmash
    setflag p1_got_rocksmash
    say "Rock Smash breaks cracked rocks. You need the Tide Badge from Port Tidewater to use it outside of battle.\pNo Pokémon needed: God Bidoof does the smashing for you!"
    say "Oh, and my partner Dudley went exploring the cave's lower floor and hasn't come back. Keep an eye out?"
    quest p1_cave_hiker start
  end
end

script p1_cc_lost_hiker
  faceplayer
  if flag p1_found_hiker
    say "DUDLEY: I'll head out once I've found one more shiny rock. Promise!"
  else
    say "DUDLEY: Huh? My partner sent you? I got so busy digging I lost track of time!"
    say "DUDLEY: Tell him I'm fine. And take this, for your trouble."
    giveitem escaperope
    say "DUDLEY: An Escape Rope zips you right out of any cave. Handy, eh?"
    setflag p1_found_hiker
    quest p1_cave_hiker next
  end
end
