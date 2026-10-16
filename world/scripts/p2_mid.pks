# Part 2: Route 7, Route 8, Quarrytown, Ruins of Sol.

script p2_r7_toolbox
  say "It's a battered toolbox stenciled \"VOLTARA POWER & LIGHT\"."
  setflag p2_found_toolbox
  hide r7_toolbox
  if quest p2_live_wire started
    quest p2_live_wire next
  end
  say "{player} picked up the toolbox. Better return it to its owner in Voltara City."
end
