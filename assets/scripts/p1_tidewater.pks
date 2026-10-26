# Port Tidewater (Part 1): rival battle, docks heist, Gym 2, ferry contract, Good Rod, pen pal exchange.

script p1_port_rival
  face rival left
  emote rival !
  say "{rival}: {player}! Wait up!"
  if flag starter_grass
    battle rival_port_cyndaquil canlose
  elif flag starter_fire
    battle rival_port_totodile canlose
  else
    battle rival_port_chikorita canlose
  end
  if result win
    say "{rival}: Argh! You're really something, you know that?"
  else
    say "{rival}: Yes! Finally! ...Don't tell anyone I celebrated."
  end
  heal
  say "{rival}: Listen. Team Eclipse is in the harbor warehouse by the docks.\pI saw them hauling crates. Marina went in after them alone!"
  say "{rival}: I'll keep watch out here. Go!"
  setflag p1_rival_port
  quest p1_light_shards next
  move rival right2
  hide rival
end

script p1_tw_gym_guard
  faceplayer
  say "Sorry, the Gym's closed! Marina ran to the harbor warehouse.\pSomething about thieves in black. It's southeast, by the piers!"
end

script p1_tw_cop
  faceplayer
  if flag p1_heist_foiled
    say "OFFICER: The warehouse is secure, thanks to you. Team Eclipse slipped away by boat, though."
  else
    say "OFFICER: I'm waiting for backup! Marina went inside already...\pYou'll help? Then hurry, but be careful!"
  end
end

script p1_wh_grunt_boss
  face wh_grunt_boss down
  say "GRUNT LEADER: Another hero? Marina's already boxed in.\pYou'll make a fine second prisoner!"
  battle p1_wh_grunt_boss
  say "GRUNT LEADER: Grr... Fine! Keep your shard! We've got plenty more to collect.\pEclipse, pull out!"
  fadeout
  setflag p1_heist_foiled
  hide wh_grunt_boss
  hide wh_grunt_a
  hide wh_grunt_b
  fadein
  quest p1_light_shards next
  call p1_wh_marina
end

script p1_wh_marina
  face marina down
  if flag p1_heist_foiled
    say "MARINA: You chased them off! I'm Marina, the Port Tidewater Gym Leader.\pThey were after the lighthouse's light shard. It's safe now, thanks to you."
    say "MARINA: Come to my Gym when you're ready. I won't go easy on you, hero!"
    fadeout
    setflag p1_marina_left
    hide marina
    fadein
  else
    say "MARINA: Careful! That one's the leader. I can't battle with these crates boxing me in!"
  end
end

script p1_gym2_sw1
  sfx switch
  setflag p1_gym2_sw1
  say "Click! A floating bridge rose across the pool!"
end

script p1_gym2_sw2
  sfx switch
  setflag p1_gym2_sw2
  say "Click! A bridge rose toward Marina's platform!"
end

script p1_gym2_guide
  faceplayer
  if badge 2
    say "The Tide Badge! Now you can use Flash and Rock Smash outside of battle.\pThose cracked rocks in Cobblecrest Cave won't stop you now!"
  else
    say "Yo, champ-in-the-making! The pools here are too deep to swim.\pStep on the floor switches to raise floating bridges. Marina waits at the far end!"
    say "Her Water types fear Electric and Grass moves. Good luck!"
  end
end

script p1_marina
  faceplayer
  if badge 2
    say "MARINA: The tide always comes back, {player}. So will I, stronger than ever!\pRoute 5 leads south to Lumen Village. There are ruins there older than the region itself."
  else
    battle p1_leader_marina
    say "MARINA: Here is the Tide Badge. You've earned it twice over."
    badge 2
    setflag p1_beat_marina
    say "MARINA: It lets you use Flash and Rock Smash outside of battle. And take this!"
    tm waterpulse
    say "MARINA: Water Pulse can confuse the target. Ride the waves, {player}!"
    quest p1_light_shards next
    quest p1_light_shards done
  end
end

script tidewater_ferry
  faceplayer
  if flag hall_of_fame
    call ferry_to_coral
  else
    say "SAILOR: The ferry to the Coral Isles isn't running for regular trainers.\pCome back once you've become Champion. Then we'll set sail!"
  end
end

script p1_tw_stones
  faceplayer
  say "MERCHANT: Evolution stones, fresh from the cliffs! Only the finest!"
  shop tidewater_stones
  say "MERCHANT: Eevee owners love my stones. Come back anytime!"
end

script p1_tw_netstall
  faceplayer
  say "Specialty balls for sea Pokémon! And TMs I fished out of the bay. Honest!"
  shop tidewater_netstall
end

script p1_tw_rod_sailor
  faceplayer
  if flag p1_got_goodrod
    say "Try the Good Rod off the piers. Horsea and Staryu love the harbor!"
  else
    say "Har! A young trainer! You remind me of me, fifty years ago."
    say "Here. This Good Rod caught me a Gyarados once. Well, a Magikarp. A big one!"
    giveitem goodrod
    setflag p1_got_goodrod
  end
end

script p1_tw_pen_pal
  faceplayer
  if quest p1_pen_pal done
    say "Shellos and I write to each other! Well, I write. Shellos squishes the paper."
  elif party has wooper
    say "A Wooper! It's even goofier than I imagined! I love it!"
    say "Here, you can have my Shellos. It always wanted to see the world!"
    givemon shellos 15
    if result 1
      quest p1_pen_pal done
    else
      say "Oh, your team is full. Make some room and come back!"
    end
  elif quest p1_pen_pal started
    say "Wooper come out at night on Route 4, near the water. Show me one, please!"
  else
    say "I've read all about Wooper but I've never seen one!"
    say "If you bring one to show me, I'll give you my Shellos. Deal?"
    quest p1_pen_pal start
  end
end
