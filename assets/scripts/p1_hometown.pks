# Hollowbrook Town and the player's home (Part 1).

script house_mom
  faceplayer
  if not flag got_starter
    say "MOM: Good morning, {player}! Professor Linden came by.\pHe asked you to visit his lab. It's the big building just south of here."
  elif not flag mom_running_shoes
    say "MOM: You got a Pokémon from the Professor? How wonderful!\pYou'll be traveling a lot now. Take these with you."
    giveitem potion 2
    say "MOM: Hold the B button to run, dear. Don't forget to call home!"
    setflag mom_running_shoes
  elif badges >= 2
    say "MOM: Two badges! The neighbors won't stop talking about you."
    heal
    say "MOM: There, all rested. Go show them, {player}!"
  else
    say "MOM: You look tired, {player}. Rest a while."
    heal
    say "MOM: All better! Off you go."
  end
end

script rival_mom
  faceplayer
  if not flag got_starter
    say "Oh, {player}! {rival} left for the lab ages ago. That kid never waits for anyone."
  elif badges >= 1
    say "{rival} called! Said you two keep bumping into each other.\pI think {rival} is secretly happy about it."
  else
    say "{rival} ran off to train. Please look out for each other out there."
  end
end

script hometown_need_starter
  say "Wait, {player}! It's dangerous to walk through tall grass without a Pokémon!"
  say "Professor Linden is waiting in the lab."
  move player down
end

script p1_hometown_fisher
  faceplayer
  if not flag got_starter
    say "I come here every morning. The pond is calm, but nothing bites!"
  elif not flag p1_got_oldrod
    say "Off on an adventure? Then you'd better take this.\pI've had no luck with it. Maybe you will!"
    giveitem oldrod
    setflag p1_got_oldrod
    say "Face any water and use the Old Rod from your bag.\pEven this little pond has a Pokémon or two."
  else
    say "Caught anything yet? Magikarp count! Sort of."
  end
end

script p1_hometown_runner
  faceplayer
  if flag mom_running_shoes
    say "Hold B to run! I race the Pidgey along Route 1 every morning."
  else
    say "Isn't Hollowbrook peaceful? The brook flows all the way down from Mt. Prism."
  end
end
