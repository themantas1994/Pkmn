# Hollowbrook Town and the player's home.

script house_mom
  faceplayer
  if not flag got_starter
    say "MOM: Good morning, {player}! Professor Linden came by.\pHe asked you to visit his lab. It's the big building just south of here."
  elif not flag mom_running_shoes
    say "MOM: You got a Pokémon from the Professor? How wonderful!\pYou'll be traveling a lot now. Take these with you."
    giveitem potion 2
    say "MOM: Hold the B button to run, dear. Don't forget to call home!"
    setflag mom_running_shoes
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
  else
    say "{rival} ran off to train. Please look out for each other out there."
  end
end

script hometown_need_starter
  say "Wait, {player}! It's dangerous to walk through tall grass without a Pokémon!"
  say "Professor Linden is waiting in the lab."
  move player down
end
