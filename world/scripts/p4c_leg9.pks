# Expansion E3, leg 9: the Eclipse remnant hunt (Agent Calloway at the Highland Lodge; cells on the Route 13 islet,
# in the Victory Road Depths and in Skyfall Basin).

script p4c_lodge_agent
  faceplayer
  if quest p4c_remnants done
    say "CALLOWAY: Sable turned herself in, the fragments are in a vault, and I'm taking a holiday. Go win that League."
  elif flag p4c_remnant_islet_done and flag p4c_remnant_depths_done and flag p4c_remnant_leader_done
    if not quest p4c_remnants started
      quest p4c_remnants start
    end
    say "CALLOWAY: All three cells, and Sable's lens fragments too? That's the last of Team Eclipse. Officially."
    say "CALLOWAY: The League pays a bounty for this kind of work. I'm told to give you these, and to say thank you. Thank you!"
    giveitem abilitypatch
    tm knockoff
    quest p4c_remnants done
  elif not flag p3b_eclipse_defeated
    say "AGENT: Agent Calloway, League security. I'm here because of Team Eclipse. Officially, I'm here for the tea."
  elif quest p4c_remnants started
    say "CALLOWAY: Leads so far: a grunt on the Route 13 lake islet, a cell somewhere under Victory Road, and their captain past the Skyfall Grotto, north up the river."
  else
    say "CALLOWAY: Agent Calloway, League security. You're the one who stopped Umbra? Then you've earned the right to hear this."
    say "CALLOWAY: Not every grunt went home. Three cells are still out there, with fragments of Umbra's lens.\pOne grunt on the Route 13 lake islet. One group under Victory Road. And their captain, Sable, somewhere past the Skyfall Grotto."
    say "CALLOWAY: I'd go myself, but those places need Surf and Waterfall, and I haven't earned the Badges for them. Can you help?"
    quest p4c_remnants start
  end
end

script p4c_remnant_progress
  if quest p4c_remnants started
    quest p4c_remnants next
  end
end

script p4c_remnant_islet
  faceplayer
  say "GRUNT: Eek! Don't look at me! I'm just a regular camper. With a black sun on my shirt. For fashion."
  battle p4c_rem_grunt_islet
  if result win
    setflag p4c_remnant_islet_done
    call p4c_remnant_progress
    say "GRUNT: Okay, okay, I'm going! Here, take this lens shard. It gives me nightmares anyway."
    fadeout
    hide p4c_r13_remnant
    fadein
  end
end

script p4c_remnant_depths
  faceplayer
  say "DR. VEXLEY: A trainer, this deep? You followed us! Team Eclipse only needs one more chance, one more lens..."
  battle p4c_rem_sci_depths
  if result win
    setflag p4c_remnant_depths_done
    call p4c_remnant_progress
    say "DR. VEXLEY: We surrender! It's very damp down here and my glasses keep fogging up."
    fadeout
    hide vd2_remnant
    hide vd2_remnant_grunt
    fadein
    say "The remnants dropped a lens fragment as they fled toward the exit."
  end
end

script p4c_remnant_leader
  faceplayer
  if not flag p3b_eclipse_defeated
    say "???: This valley is closed. Go away."
  else
    say "SABLE: So the kid who beat Umbra found my little camp. I'm Sable, captain of what's left of Team Eclipse."
    say "SABLE: Umbra wanted a world without harsh light. I just wanted somewhere to belong. Funny how those turned out the same."
    battle p4c_rem_captain_sable
    if result win
      setflag p4c_remnant_leader_done
      call p4c_remnant_progress
      say "SABLE: Take the fragments. Tell your agent friend I'll walk down to the lodge myself. Maybe the tea is good."
      fadeout
      hide sb_remnant_leader
      fadein
    end
  end
end
