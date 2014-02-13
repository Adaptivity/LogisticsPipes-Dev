/** 
 * Copyright (c) Krapht, 2011
 * 
 * "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public 
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package logisticspipes.pipes.basic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.PriorityBlockingQueue;

import logisticspipes.Configs;
import logisticspipes.LogisticsPipes;
import logisticspipes.api.ILogisticsPowerProvider;
import logisticspipes.api.IRoutedPowerProvider;
import logisticspipes.blocks.LogisticsSecurityTileEntity;
import logisticspipes.interfaces.ISecurityProvider;
import logisticspipes.interfaces.IWatchingHandler;
import logisticspipes.interfaces.IWorldProvider;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.interfaces.routing.IRequireReliableFluidTransport;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.items.LogisticsFluidContainer;
import logisticspipes.logisticspipes.IAdjacentWorldAccess;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.ITrackStatistics;
import logisticspipes.logisticspipes.PipeTransportLayer;
import logisticspipes.logisticspipes.RouteLayer;
import logisticspipes.logisticspipes.TransportLayer;
import logisticspipes.modules.LogisticsGuiModule;
import logisticspipes.modules.LogisticsModule;
import logisticspipes.network.GuiIDs;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.PacketPayload;
import logisticspipes.network.TilePacketWrapper;
import logisticspipes.network.packets.pipe.RequestRoutingLasersPacket;
import logisticspipes.network.packets.pipe.StatUpdate;
import logisticspipes.pipefxhandlers.Particles;
import logisticspipes.pipes.upgrades.UpgradeManager;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.buildcraft.BuildCraftProxy;
import logisticspipes.proxy.buildcraft.gates.ActionDisableLogistics;
import logisticspipes.proxy.cc.interfaces.CCCommand;
import logisticspipes.proxy.cc.interfaces.CCType;
import logisticspipes.renderer.LogisticsHUDRenderer;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.RoutedEntityItem;
import logisticspipes.routing.ServerRouter;
import logisticspipes.security.PermissionException;
import logisticspipes.security.SecuritySettings;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.textures.provider.LPPipeIconProvider;
import logisticspipes.ticks.QueuedTasks;
import logisticspipes.transport.PipeTransportLogistics;
import logisticspipes.utils.AdjacentTile;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.InventoryHelper;
import logisticspipes.utils.OrientationsUtil;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.WorldUtil;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;
import logisticspipes.utils.tuples.Triplet;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatMessageComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@CCType(name = "LogisticsPipes:Normal")
public abstract class CoreRoutedPipe implements IRequestItems, IAdjacentWorldAccess, ITrackStatistics, IWorldProvider, IWatchingHandler, IRoutedPowerProvider {

	public enum ItemSendMode {
		Normal,
		Fast
	}

	private boolean init = false;
	
	protected IRouter router;
	protected String routerId;
	protected Object routerIdLock = new Object();
	private static int pipecount = 0;
	protected int _delayOffset = 0;
	
	private boolean _textureBufferPowered;
	
	protected boolean _initialInit = true;
	
	private boolean enabled = true;
	private int cachedItemID = -1;
	private boolean blockRemove = false;
	private boolean destroyByPlayer = false;
	
	public long delayTo = 0;
	public int repeatFor = 0;
	
	protected RouteLayer _routeLayer;
	protected TransportLayer _transportLayer;
	protected final PriorityBlockingQueue<IRoutedItem> _inTransitToMe = new PriorityBlockingQueue<IRoutedItem>(10,new IRoutedItem.DelayComparator());
	
	private UpgradeManager upgradeManager = new UpgradeManager(this);
	
	public int stat_session_sent;
	public int stat_session_recieved;
	public int stat_session_relayed;
	
	public long stat_lifetime_sent;
	public long stat_lifetime_recieved;
	public long stat_lifetime_relayed;
	
	public int server_routing_table_size = 0;
	
	protected final LinkedList<Triplet<IRoutedItem, ForgeDirection, ItemSendMode>> _sendQueue = new LinkedList<Triplet<IRoutedItem, ForgeDirection, ItemSendMode>>();
	
	protected final ArrayList<TravelingItem> queuedDataForUnroutedItems = new ArrayList<TravelingItem>();
	
	public final PlayerCollectionList watchers = new PlayerCollectionList();

	protected List<IInventory> _cachedAdjacentInventories;

	protected int throttleTime = 20;
	private int throttleTimeLeft = 20 + new Random().nextInt(Configs.LOGISTICS_DETECTION_FREQUENCY);

	public int[] signalStrength = new int[]{0, 0, 0, 0};
	public LogisticsTileGenericPipe container;
	public final PipeTransportLogistics transport;
	public final int itemID;
	public boolean[] wireSet = new boolean[] {false, false, false, false};
	@SuppressWarnings("rawtypes")
	private static Map<Class, TilePacketWrapper> networkWrappers = new HashMap<Class, TilePacketWrapper>();
	
	public CoreRoutedPipe(int itemID) {
		this(new PipeTransportLogistics(), itemID);
	}

	public CoreRoutedPipe(PipeTransportLogistics transport, int itemID) {
		this.transport = transport;
		this.itemID = itemID;

		if (!networkWrappers.containsKey(this.getClass())) {
			networkWrappers.put(this.getClass(), new TilePacketWrapper(new Class[]{TileGenericPipe.class, this.transport.getClass()}));
		}
		((PipeTransportLogistics) transport).allowBouncing = true;
		
		pipecount++;
		
		//Roughly spread pipe updates throughout the frequency, no need to maintain balance
		_delayOffset = pipecount % Configs.LOGISTICS_DETECTION_FREQUENCY; 
	}

	public RouteLayer getRouteLayer(){
		if (_routeLayer == null){
			_routeLayer = new RouteLayer(getRouter(), getTransportLayer());
		}
		return _routeLayer;
	}
	
	public TransportLayer getTransportLayer()
	{
		if (_transportLayer == null) {
			_transportLayer = new PipeTransportLayer(this, this, getRouter());
		}
		return _transportLayer;
	}

	public UpgradeManager getUpgradeManager() {
		return upgradeManager;
	}
	
	public PacketPayload getLogisticsNetworkPacket() {
		PacketPayload payload = new TilePacketWrapper(new Class[] { container.getClass(), transport.getClass(), this.getClass()}).toPayload(getX(), getY(), getZ(), new Object[] { container, transport, this });

		return payload;
	}
	
	public void queueRoutedItem(IRoutedItem routedItem, ForgeDirection from) {
		_sendQueue.addLast(new Triplet<IRoutedItem, ForgeDirection, ItemSendMode>(routedItem, from, ItemSendMode.Normal));
		sendQueueChanged(false);
	}

	public void queueRoutedItem(IRoutedItem routedItem, ForgeDirection from, ItemSendMode mode) {
		_sendQueue.addLast(new Triplet<IRoutedItem, ForgeDirection, ItemSendMode>(routedItem, from, mode));
		sendQueueChanged(false);
	}
	/** 
	 * @param force  == true never delegates to a thread
	 * @return number of things sent.
	 */
	public int sendQueueChanged(boolean force) {return 0;}
	
	private void sendRoutedItem(IRoutedItem routedItem, ForgeDirection from){
		Position p = new Position(this.getX() + 0.5F, this.getY() + CoreConstants.PIPE_MIN_POS, this.getZ() + 0.5F, from);
		if(from == ForgeDirection.DOWN) {
			p.moveForwards(0.24F);
		} else if(from == ForgeDirection.UP) {
			p.moveForwards(0.74F);
		} else {
			p.moveForwards(0.49F);
		}
		routedItem.SetPosition(p.x, p.y, p.z);
		((PipeTransportItems) transport).injectItem(routedItem.getTravelingItem(), from.getOpposite());
		
		IRouter r = SimpleServiceLocator.routerManager.getRouterUnsafe(routedItem.getDestination(),false);
		if(r != null) {
			CoreRoutedPipe pipe = r.getCachedPipe();
			if(pipe !=null) // pipes can unload at inconvenient times ...
				pipe.notifyOfSend(routedItem);
			else {
				//TODO: handle sending items to known chunk-unloaded destination?
			}
		} // should not be able to send to a non-existing router
		//router.startTrackingRoutedItem((RoutedEntityItem) routedItem.getTravelingItem());
		MainProxy.sendSpawnParticlePacket(Particles.OrangeParticle, this.getX(), this.getY(), this.getZ(), this.getWorld(), 2);
		stat_lifetime_sent++;
		stat_session_sent++;
		updateStats();
	}
	
	private void notifyOfSend(IRoutedItem routedItem) {
		this._inTransitToMe.add(routedItem);
		//LogisticsPipes.log.info("Sending: "+routedItem.getIDStack().getItem().getFriendlyName());
	}

	public abstract ItemSendMode getItemSendMode();
	
	/*
	private boolean checkTileEntity(boolean force) {
		if(getWorld().getTotalWorldTime() % 10 == 0 || force) {
			if(!(this.container instanceof LogisticsTileGenericPipe)) {
				TileEntity tile = getWorld().getBlockTileEntity(getX(), getY(), getZ());
				if(tile != this.container) {
					LogisticsPipes.log.severe("LocalCodeError");
				}
				if(MainProxy.isClient(getWorld())) {
					WorldTickHandler.clientPipesToReplace.add(this.container);
				} else {
					WorldTickHandler.serverPipesToReplace.add(this.container);
				}
				return true;
			}
		}
		return false;
	}
	*/
	
	/**
	 * Designed to help protect against routing loops - if both pipes are on the same block, and of ISided overlapps, return true
	 * @param other
	 * @return boolean indicating if both pull from the same inventory.
	 */
	public boolean sharesInventoryWith(CoreRoutedPipe other){
		List<IInventory> others = other.getConnectedRawInventories();
		if(others==null || others.size()==0)
			return false;
		for(IInventory i : getConnectedRawInventories()) {
			if(others.contains(i)) {
				return true;
			}
		}
		return false;
	}
	
	protected List<IInventory> getConnectedRawInventories()	{
		if(_cachedAdjacentInventories != null) {
			return _cachedAdjacentInventories;
		}
		WorldUtil worldUtil = new WorldUtil(this.getWorld(), this.getX(), this.getY(), this.getZ());
		LinkedList<IInventory> adjacent = new LinkedList<IInventory>();
		for (AdjacentTile tile : worldUtil.getAdjacentTileEntities(true)){
			if (tile.tile instanceof TileGenericPipe) continue;
			if (!(tile.tile instanceof IInventory)) continue;
			adjacent.add(InventoryHelper.getInventory((IInventory)tile.tile));
		}
		_cachedAdjacentInventories=adjacent;
		return _cachedAdjacentInventories;
	}

	/***
	 * first tick just create a router and do nothing.
	 */
	public void firstInitialiseTick() {
		getRouter();
	}
	
	/*** 
	 * Only Called Server Side
	 * Only Called when the pipe is enabled
	 */
	public void enabledUpdateEntity() {}
	
	/***
	 * Called Server and Client Side
	 * Called every tick
	 */
	public void ignoreDisableUpdateEntity() {}
	
	public final void updateEntity() {
		if(!init) {
			init = true;
			firstInitialiseTick();
			return;
		}
		if(repeatFor > 0) {
			if(delayTo < System.currentTimeMillis()) {
				delayTo = System.currentTimeMillis() + 200;
				repeatFor--;
				getWorld().markBlockForUpdate(this.getX(), this.getY(), this.getZ());
			}
		}

		// remove old items _inTransit -- these should have arrived, but have probably been lost instead. In either case, it will allow a re-send so that another attempt to re-fill the inventory can be made.		
		while(this._inTransitToMe.peek()!=null && this._inTransitToMe.peek().getTickToTimeOut()<=0){
			final IRoutedItem p=_inTransitToMe.poll();
			if (LogisticsPipes.DEBUG) {
					LogisticsPipes.log.info("Timed Out: "+p.getIDStack().getItem().getFriendlyName());
			}
		}
		//update router before ticking logic/transport
		getRouter().update(getWorld().getTotalWorldTime() % Configs.LOGISTICS_DETECTION_FREQUENCY == _delayOffset || _initialInit);
		getUpgradeManager().securityTick();

		transport.updateEntity();

		// Do not try to update gates client side.
		if (container.worldObj.isRemote)
			return;

		// from BaseRoutingLogic
		if (--throttleTimeLeft <= 0) {
			throttledUpdateEntity();
			throttleTimeLeft = throttleTime;
		}
		
		ignoreDisableUpdateEntity();
		_initialInit = false;
		if (!_sendQueue.isEmpty()){
			if(getItemSendMode() == ItemSendMode.Normal) {
				Triplet<IRoutedItem, ForgeDirection, ItemSendMode> itemToSend = _sendQueue.getFirst();
				sendRoutedItem(itemToSend.getValue1(), itemToSend.getValue2());
				_sendQueue.removeFirst();
				for(int i=0;i < 16 && !_sendQueue.isEmpty() && _sendQueue.getFirst().getValue3() == ItemSendMode.Fast;i++) {
					if (!_sendQueue.isEmpty()){
						itemToSend = _sendQueue.getFirst();
						sendRoutedItem(itemToSend.getValue1(), itemToSend.getValue2());
						_sendQueue.removeFirst();
					}
				}
				sendQueueChanged(false);
			} else if(getItemSendMode() == ItemSendMode.Fast) {
				for(int i=0;i < 16;i++) {
					if (!_sendQueue.isEmpty()){
						Triplet<IRoutedItem, ForgeDirection, ItemSendMode> itemToSend = _sendQueue.getFirst();
						sendRoutedItem(itemToSend.getValue1(), itemToSend.getValue2());
						_sendQueue.removeFirst();
					}
				}
				sendQueueChanged(false);
			} else if(getItemSendMode() == null) {
				throw new UnsupportedOperationException("getItemSendMode() can't return null. "+this.getClass().getName());
			} else {
				throw new UnsupportedOperationException("getItemSendMode() returned unhandled value. " + getItemSendMode().name() + " in "+this.getClass().getName());
			}
		}
		if(MainProxy.isClient(getWorld())) return;
		checkTexturePowered();
		if (!isEnabled()) return;
		enabledUpdateEntity();
		if (getLogisticsModule() == null) return;
		getLogisticsModule().tick();
	}	

	protected void onAllowedRemoval() {}

// From BaseRoutingLogic
	public void throttledUpdateEntity(){}
	
	protected void delayThrottle() {
		//delay 6(+1) ticks to prevent suppliers from ticking between a item arriving at them and the item hitting their adj. inv
		if(throttleTimeLeft < 7)
			throttleTimeLeft = 7;
	}
	
	private void doDebugStuff(EntityPlayer entityplayer) {
		//entityplayer.worldObj.setWorldTime(4951);
		IRouter r = getRouter();
		if(!(r instanceof ServerRouter)) return;
		System.out.println("***");
		System.out.println("---------Interests---------------");
		for(Entry<ItemIdentifier, Set<IRouter>> i: ServerRouter.getInterestedInSpecifics().entrySet()){
			System.out.print(i.getKey().getFriendlyName()+":");
			for(IRouter j:i.getValue())
				System.out.print(j.getSimpleID()+",");
			System.out.println();
		}
		
		System.out.print("ALL ITEMS:");
		for(IRouter j:ServerRouter.getInterestedInGeneral())
			System.out.print(j.getSimpleID()+",");
		System.out.println();
			
		
		
		
		ServerRouter sr = (ServerRouter) r;
		
		System.out.println(r.toString());
		System.out.println("---------CONNECTED TO---------------");
		for (CoreRoutedPipe adj : sr._adjacent.keySet()) {
			System.out.println(adj.getRouter().getSimpleID());
		}
		System.out.println();
		System.out.println("========DISTANCE TABLE==============");
		for(ExitRoute n : r.getIRoutersByCost()) {
			System.out.println(n.destination.getSimpleID()+ " @ " + n.distanceToDestination + " -> "+ n.connectionDetails +"("+n.destination.getId() +")");
		}
		System.out.println();
		System.out.println("*******EXIT ROUTE TABLE*************");
		List<List<ExitRoute>> table = r.getRouteTable();
		for (int i=0; i < table.size(); i++){			
			if(table.get(i) != null) {
				if(table.get(i).size() > 0) {
					System.out.println(i + " -> " + table.get(i).get(0).destination.getSimpleID());
					for(ExitRoute route:table.get(i)) {
						System.out.println("\t\t via " + route.exitOrientation + "(" + route.distanceToDestination + " distance)");
					}
				}
			}
		}
		System.out.println();
		System.out.println("++++++++++CONNECTIONS+++++++++++++++");
		System.out.println(Arrays.toString(ForgeDirection.VALID_DIRECTIONS));
		System.out.println(Arrays.toString(sr.sideDisconnected));
		System.out.println(Arrays.toString(container.pipeConnectionsBuffer));
		System.out.println();
		System.out.println("~~~~~~~~~~~~~~~POWER~~~~~~~~~~~~~~~~");
		System.out.println(r.getPowerProvider());
		System.out.println();
		System.out.println("################END#################");
		refreshConnectionAndRender(true);
		System.out.print("");
		sr.CreateRouteTable(Integer.MAX_VALUE);
	}
// end FromBaseRoutingLogic
	
	public final void onBlockRemoval() {
		revertItemID();
		if(canBeDestroyed() || destroyByPlayer) {
			try {
				onAllowedRemoval();

				//TODO
				/*
				for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
					if (container.hasFacade(direction)) {
						container.dropFacade(direction);
					}
					if (container.hasPlug(direction)) {
						container.removeAndDropPlug(direction);
					}
				}
				*/
				//invalidate() removes the router
//				if (logic instanceof BaseRoutingLogic){
//					((BaseRoutingLogic)logic).destroy();
//				}
				//Just in case
				pipecount = Math.max(pipecount - 1, 0);
				
				if (transport != null && transport instanceof PipeTransportLogistics){
					transport.dropBuffer();
				}
				getUpgradeManager().dropUpgrades();
			} catch(Exception e) {
				e.printStackTrace();
			}
		} else if(!blockRemove) {
			final World worldCache = getWorld();
			final int xCache = getX();
			final int yCache = getY();
			final int zCache = getZ();
			final TileEntity tileCache = this.container;
			blockRemove = true;
			QueuedTasks.queueTask(new Callable<Object>() {
				@Override
				public Object call() throws Exception {
					tileCache.validate();
					worldCache.setBlock(xCache, yCache, zCache, LogisticsPipes.LogisticsBlockGenericPipe.blockID);
					worldCache.setBlockTileEntity(xCache, yCache, zCache, tileCache);
					worldCache.notifyBlockChange(xCache, yCache, zCache, LogisticsPipes.LogisticsBlockGenericPipe.blockID);
					blockRemove = false;
					return null;
				}
			});
		}
	}
	
	public void invalidate() {
		if(router != null) {
			router.destroy();
			router = null;
		}
	}
	
	public void onChunkUnload() {
		if(router != null) {
			router.clearPipeCache();
			router.clearInterests();
		}
	}
	
	public void dropContents() {
		if(MainProxy.isClient(getWorld())) return;
		if(canBeDestroyed() || destroyByPlayer) {
			transport.dropContents();
		} else {
			cachedItemID = itemID;
			itemID =  LogisticsPipes.LogisticsBrokenItem.itemID;
			final World worldCache = getWorld();
			final int xCache = getX();
			final int yCache = getY();
			final int zCache = getZ();
			final TileEntity tileCache = this.container;
			blockRemove = true;
			QueuedTasks.queueTask(new Callable<Object>() {
				@Override
				public Object call() throws Exception {
					revertItemID();
					worldCache.setBlock(xCache, yCache, zCache, LogisticsPipes.LogisticsBlockGenericPipe.blockID);
					worldCache.setBlockTileEntity(xCache, yCache, zCache, tileCache);
					worldCache.notifyBlockChange(xCache, yCache, zCache, LogisticsPipes.LogisticsBlockGenericPipe.blockID);
					blockRemove = false;
					return null;
				}
			});
		}
	}

	private void revertItemID() {
		if(cachedItemID != -1) {
			try {
				itemID = cachedItemID;
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			cachedItemID = -1;
		}
	}

	public void checkTexturePowered() {
		if(Configs.LOGISTICS_POWER_USAGE_DISABLED) return;
		if(getWorld().getTotalWorldTime() % 10 != 0) return;
		if(_initialInit || router == null) return;
		boolean flag;
		if((flag = canUseEnergy(1)) != _textureBufferPowered) {
			_textureBufferPowered = flag;
			refreshRender(false);
			MainProxy.sendSpawnParticlePacket(Particles.RedParticle, this.getX(), this.getY(), this.getZ(), this.getWorld(), 3);
		}
	}
	
	
	public abstract TextureType getCenterTexture();
	
	public TextureType getTextureType(ForgeDirection connection) {
		if(_initialInit)
			return getCenterTexture();

		if (connection == ForgeDirection.UNKNOWN){
			return getCenterTexture();
		} else if ((router != null) && getRouter().isRoutedExit(connection)) {
			return getRoutedTexture(connection);
			
		} else {
			return getNonRoutedTexture(connection);
		}
	}
	
	public TextureType getRoutedTexture(ForgeDirection connection){
		return Textures.LOGISTICSPIPE_ROUTED_TEXTURE;
	}
	
	public TextureType getNonRoutedTexture(ForgeDirection connection){
		return Textures.LOGISTICSPIPE_NOTROUTED_TEXTURE;
	}
	
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		transport.writeToNBT(nbttagcompound);

		synchronized (routerIdLock) {
			if (routerId == null || routerId.isEmpty()){
				if(router != null)
					routerId = router.getId().toString();
				else
					routerId = UUID.randomUUID().toString();
			}
		}
		nbttagcompound.setString("routerId", routerId);
		nbttagcompound.setLong("stat_lifetime_sent", stat_lifetime_sent);
		nbttagcompound.setLong("stat_lifetime_recieved", stat_lifetime_recieved);
		nbttagcompound.setLong("stat_lifetime_relayed", stat_lifetime_relayed);
		if (getLogisticsModule() != null){
			getLogisticsModule().writeToNBT(nbttagcompound);
		}
		NBTTagCompound upgradeNBT = new NBTTagCompound();
		upgradeManager.writeToNBT(upgradeNBT);
		nbttagcompound.setCompoundTag("upgradeManager", upgradeNBT);

		NBTTagList sendqueue = new NBTTagList();
		for(Triplet<IRoutedItem, ForgeDirection, ItemSendMode> p : _sendQueue) {
			NBTTagCompound tagentry = new NBTTagCompound();
			NBTTagCompound tagentityitem = new NBTTagCompound();
			p.getValue1().getTravelingItem().writeToNBT(tagentityitem);
			tagentry.setCompoundTag("entityitem", tagentityitem);
			tagentry.setByte("from", (byte)(p.getValue2().ordinal()));
			tagentry.setByte("mode", (byte)(p.getValue3().ordinal()));
			sendqueue.appendTag(tagentry);
		}
		nbttagcompound.setTag("sendqueue", sendqueue);
	}
	
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		transport.readFromNBT(nbttagcompound);

		synchronized (routerIdLock) {
			routerId = nbttagcompound.getString("routerId");
		}
		
		stat_lifetime_sent = nbttagcompound.getLong("stat_lifetime_sent");
		stat_lifetime_recieved = nbttagcompound.getLong("stat_lifetime_recieved");
		stat_lifetime_relayed = nbttagcompound.getLong("stat_lifetime_relayed");
		if (getLogisticsModule() != null){
			getLogisticsModule().readFromNBT(nbttagcompound);
		}
		upgradeManager.readFromNBT(nbttagcompound.getCompoundTag("upgradeManager"));

		_sendQueue.clear();
		NBTTagList sendqueue = nbttagcompound.getTagList("sendqueue");
		for(int i = 0; i < sendqueue.tagCount(); i++) {
			NBTTagCompound tagentry = (NBTTagCompound)sendqueue.tagAt(i);
			NBTTagCompound tagentityitem = tagentry.getCompoundTag("entityitem");
			TravelingItem entity = new TravelingItem();
			entity.readFromNBT(tagentityitem);
			IRoutedItem routeditem = SimpleServiceLocator.buildCraftProxy.CreateRoutedItem(entity);
			ForgeDirection from = ForgeDirection.values()[tagentry.getByte("from")];
			ItemSendMode mode = ItemSendMode.values()[tagentry.getByte("mode")];
			_sendQueue.add(new Triplet<IRoutedItem, ForgeDirection, ItemSendMode>(routeditem, from, mode));
		}
	}
	
	@Override
	public IRouter getRouter() {
		if (router == null){
			synchronized (routerIdLock) {
				
				UUID routerIntId = null;
				if(routerId!=null && !routerId.isEmpty())
					routerIntId = UUID.fromString(routerId);
				router = SimpleServiceLocator.routerManager.getOrCreateRouter(routerIntId, MainProxy.getDimensionForWorld(getWorld()), getX(), getY(), getZ(), false);
			}
		}
		return router;
	}
	
	public boolean isEnabled(){
		return enabled;
	}
	
	public void setEnabled(boolean enabled){
		this.enabled = enabled; 
	}

	public void onNeighborBlockChange(int blockId) {
		transport.onNeighborBlockChange(blockId);
		clearCache();
		if(MainProxy.isServer(getWorld())) {
			onNeighborBlockChange_Logistics();
		}
	}

	public void onNeighborBlockChange_Logistics(){}
	
	public abstract LogisticsModule getLogisticsModule();
	
	public final boolean blockActivated(EntityPlayer entityplayer) {
		
		
		SecuritySettings settings = null;
		if(MainProxy.isServer(entityplayer.worldObj)) {
			LogisticsSecurityTileEntity station = SimpleServiceLocator.securityStationManager.getStation(getUpgradeManager().getSecurityID());
			// Logic had false
			if(station != null) {
				settings = station.getSecuritySettingsForPlayer(entityplayer, true);
			}
		}
		if(handleClick(entityplayer, settings)) return true;
		if (SimpleServiceLocator.buildCraftProxy.isWrenchEquipped(entityplayer) && !(entityplayer.isSneaking()) && SimpleServiceLocator.buildCraftProxy.canWrench(entityplayer, this.getX(), this.getY(), this.getZ())) {
			if(wrenchClicked(entityplayer, settings)) {
				SimpleServiceLocator.buildCraftProxy.wrenchUsed(entityplayer, this.getX(), this.getY(), this.getZ());
				return true;
			}
			SimpleServiceLocator.buildCraftProxy.wrenchUsed(entityplayer, this.getX(), this.getY(), this.getZ());
		}
		if(SimpleServiceLocator.buildCraftProxy.isUpgradeManagerEquipped(entityplayer) && !(entityplayer.isSneaking())) {
			if(MainProxy.isServer(getWorld())) {
				if (settings == null || settings.openUpgrades) {
					getUpgradeManager().openGui(entityplayer, this);
				} else {
					entityplayer.sendChatToPlayer(ChatMessageComponent.createFromText("Permission denied"));
				}
			}
			return true;
		}
		if(!(entityplayer.isSneaking()) && getUpgradeManager().tryIserting(getWorld(), entityplayer)) {
			return true;
		}
		//TODO: simplify any duplicate logic from above
		// from logic
		if (entityplayer.getCurrentEquippedItem() == null) {
			if (!entityplayer.isSneaking()) return false;
			if(MainProxy.isClient(entityplayer.worldObj)) {
				if(!LogisticsHUDRenderer.instance().hasLasers()) { //TODO remove old Lasers
					MainProxy.sendPacketToServer(PacketHandler.getPacket(RequestRoutingLasersPacket.class).setPosX(getX()).setPosY(getY()).setPosZ(getZ()));
				} else {
					LogisticsHUDRenderer.instance().resetLasers();
				}
			}
			if (LogisticsPipes.DEBUG) {
				doDebugStuff(entityplayer);
			}
			return true;
		} else if (entityplayer.getCurrentEquippedItem().getItem() == LogisticsPipes.LogisticsNetworkMonitior && (settings == null || settings.openNetworkMonitor)) {
			if(MainProxy.isServer(entityplayer.worldObj)) {
				entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_RoutingStats_ID, getWorld(), getX(), getY(), getZ());
			}
			return true;
		} else if (SimpleServiceLocator.buildCraftProxy.isWrenchEquipped(entityplayer) && (settings == null || settings.openGui) && SimpleServiceLocator.buildCraftProxy.canWrench(entityplayer, this.getX(), this.getY(), this.getZ())) {
			onWrenchClicked(entityplayer);
			SimpleServiceLocator.buildCraftProxy.wrenchUsed(entityplayer, this.getX(), this.getY(), this.getZ());
			return true;
		} else if (entityplayer.getCurrentEquippedItem().getItem() == LogisticsPipes.LogisticsRemoteOrderer && (settings == null || settings.openRequest)) {
			if(MainProxy.isServer(entityplayer.worldObj)) {
				entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_Normal_Orderer_ID, getWorld(), getX(), getY(), getZ());
			}
			return true;
		} else if(entityplayer.getCurrentEquippedItem().getItem() == LogisticsPipes.LogisticsRemoteOrderer) {
			if(MainProxy.isServer(entityplayer.worldObj)) {
				entityplayer.sendChatToPlayer(ChatMessageComponent.createFromText("Permission denied"));
			}
			return true;
		} else if(entityplayer.getCurrentEquippedItem().getItem() == LogisticsPipes.LogisticsNetworkMonitior) {
			if(MainProxy.isServer(entityplayer.worldObj)) {
				entityplayer.sendChatToPlayer(ChatMessageComponent.createFromText("Permission denied"));
			}
			return true;
		}
		return false;
	}
	
	protected boolean handleClick(EntityPlayer entityplayer, SecuritySettings settings) {
		return false;
	}
	
	protected boolean wrenchClicked(EntityPlayer entityplayer, SecuritySettings settings) {
		if (getLogisticsModule() != null && getLogisticsModule() instanceof LogisticsGuiModule) {
			if(MainProxy.isServer(getWorld())) {
				if (settings == null || settings.openGui) {
					entityplayer.openGui(LogisticsPipes.instance, ((LogisticsGuiModule)getLogisticsModule()).getGuiHandlerID(), getWorld(), getX(), getY(), getZ());
				} else {
					entityplayer.sendChatToPlayer(ChatMessageComponent.createFromText("Permission denied"));
				}
			}
			return true;
		}
		return false;
	}
	
	protected void clearCache() {
		_cachedAdjacentInventories=null;
	}
	
	public void refreshRender(boolean spawnPart) {
		
		this.container.scheduleRenderUpdate();
		if (spawnPart) {
			MainProxy.sendSpawnParticlePacket(Particles.GreenParticle, this.getX(), this.getY(), this.getZ(), this.getWorld(), 3);
		}
	}
	
	public void refreshConnectionAndRender(boolean spawnPart) {
		clearCache();
		this.container.scheduleNeighborChange();
		if (spawnPart) {
			MainProxy.sendSpawnParticlePacket(Particles.GreenParticle, this.getX(), this.getY(), this.getZ(), this.getWorld(), 3);
		}
	}
	
	/***  --  IAdjacentWorldAccess  --  ***/
	
	@Override
	public LinkedList<AdjacentTile> getConnectedEntities() {
		WorldUtil world = new WorldUtil(this.getWorld(), this.getX(), this.getY(), this.getZ());
		LinkedList<AdjacentTile> adjacent = world.getAdjacentTileEntities(true);
		
		Iterator<AdjacentTile> iterator = adjacent.iterator();
		while (iterator.hasNext()){
			AdjacentTile tile = iterator.next();
			if (!SimpleServiceLocator.buildCraftProxy.checkPipesConnections(this.container, tile.tile, tile.orientation)){
				iterator.remove();
			}
		}
		
		return adjacent;
	}
	
	@Override
	public int getRandomInt(int maxSize) {
		return getWorld().rand.nextInt(maxSize);
	}
	
	/***  --  ITrackStatistics  --  ***/

	@Override
	public void recievedItem(int count) {
		stat_session_recieved += count;
		stat_lifetime_recieved += count;
		updateStats();
	}
	
	@Override
	public void relayedItem(int count) {
		stat_session_relayed += count;
		stat_lifetime_relayed += count;
		updateStats();
	}

	@Override
	public World getWorld() {
		return container.getWorld();
	}

	@Override
	public void playerStartWatching(EntityPlayer player, int mode) {
		if(mode == 0) {
			watchers.add(player);
			MainProxy.sendPacketToPlayer(PacketHandler.getPacket(StatUpdate.class).setPipe(this), (Player)player);
		}
	}

	@Override
	public void playerStopWatching(EntityPlayer player, int mode) {
		if(mode == 0) {
			watchers.remove(player);
		}
	}
	
	public void updateStats() {
		if(watchers.size() > 0) {
			MainProxy.sendToPlayerList(PacketHandler.getPacket(StatUpdate.class).setPipe(this), watchers);
		}
	}
	
	@Override
	public void itemCouldNotBeSend(ItemIdentifierStack item) {
		if(this instanceof IRequireReliableTransport) {
			((IRequireReliableTransport)this).itemLost(item);
		}
		//Override by subclasses //TODO
	}

	public boolean isLockedExit(ForgeDirection orientation) {
		return false;
	}
	
	public boolean logisitcsIsPipeConnected(TileEntity tile, ForgeDirection dir) {
		return false;
	}
	
	public boolean disconnectPipe(TileEntity tile, ForgeDirection dir) {
		return false;
	}
	
	/*public final boolean canPipeConnect(TileEntity tile, ForgeDirection dir) {
		return canPipeConnect(tile, dir, false);
	}
	*/
	
	public boolean globalIgnoreConnectionDisconnection = false;
	
	public final boolean canPipeConnect(TileEntity tile, ForgeDirection dir, boolean ignoreSystemDisconnection) {
		ForgeDirection side = OrientationsUtil.getOrientationOfTilewithPipe(this.transport, tile);
		if(getUpgradeManager().isSideDisconnected(side)) {
			return false;
		}
		if(container != null && side != ForgeDirection.UNKNOWN/* && container.hasPlug(side)*/) {
			return false;
		}
		if(getRouter().isSideDisconneceted(side) && !ignoreSystemDisconnection && !globalIgnoreConnectionDisconnection) {
			return false;
		}
		return (transport.canPipeConnect(tile, dir) || logisitcsIsPipeConnected(tile, dir)) && !disconnectPipe(tile, dir);
	}
	
	public void connectionUpdate() {
		if(container != null) {
			container.scheduleNeighborChange();
			getWorld().notifyBlockChange(getX(), getY(), getZ(), getWorld().getBlockId(getX(), getY(), getZ()));
		}
	}
	
	public UUID getSecurityID() {
		return getUpgradeManager().getSecurityID();
	}

	public void insetSecurityID(UUID id) {
		getUpgradeManager().insetSecurityID(id);
	}
	
	/* Power System */

	public List<Pair<ILogisticsPowerProvider,List<IFilter>>> getRoutedPowerProviders() {
		if(MainProxy.isClient(getWorld())) {
			return null;
		}
		return this.getRouter().getPowerProvider();
	}
	
	@Override
	public boolean useEnergy(int amount){
		return useEnergy(amount, null, true);
	}
	@Override
	public boolean canUseEnergy(int amount){
		return canUseEnergy(amount,null);
	}

	@Override
	public boolean canUseEnergy(int amount, List<Object> providersToIgnore) {
		if(MainProxy.isClient(getWorld())) return false;
		if(Configs.LOGISTICS_POWER_USAGE_DISABLED) return true;
		if(amount == 0) return true;
		if(providersToIgnore !=null && providersToIgnore.contains(this))
			return false;
		List<Pair<ILogisticsPowerProvider,List<IFilter>>> list = getRoutedPowerProviders();
		if(list == null) return false;
outer:
		for(Pair<ILogisticsPowerProvider,List<IFilter>> provider: list) {
			for(IFilter filter:provider.getValue2()) {
				if(filter.blockPower()) continue outer;
			}
			if(provider.getValue1().canUseEnergy(amount, providersToIgnore)) {
				return true;
			}
		}
		return false;
	}
	
	@Override
	public boolean useEnergy(int amount, List<Object> providersToIgnore) {
		return useEnergy(amount, providersToIgnore, false);
	}

	private boolean useEnergy(int amount, List<Object> providersToIgnore, boolean sparkles) {
		if(MainProxy.isClient(getWorld())) return false;
		if(Configs.LOGISTICS_POWER_USAGE_DISABLED) return true;
		if(amount == 0) return true;
		if(providersToIgnore==null)
			providersToIgnore = new ArrayList<Object>();
		if(providersToIgnore.contains(this))
			return false;
		providersToIgnore.add(this);
		List<Pair<ILogisticsPowerProvider,List<IFilter>>> list = getRoutedPowerProviders();
		if(list == null) return false;
outer:
		for(Pair<ILogisticsPowerProvider,List<IFilter>> provider: list) {
			for(IFilter filter:provider.getValue2()) {
				if(filter.blockPower()) continue outer;
			}
			if(provider.getValue1().canUseEnergy(amount, providersToIgnore)) {
				if(provider.getValue1().useEnergy(amount, providersToIgnore)) {
					if(sparkles) {
						int particlecount = amount;
						if (particlecount > 10) {
							particlecount = 10;
						}
						MainProxy.sendSpawnParticlePacket(Particles.GoldParticle, this.getX(), this.getY(), this.getZ(), this.getWorld(), particlecount);
					}
					return true;
				}
			}
		}
		return false;
	}
	
	public void queueEvent(String event, Object[] arguments) {
		if(this.container instanceof LogisticsTileGenericPipe) {
			((LogisticsTileGenericPipe)this.container).queueEvent(event, arguments);
		}
	}
	
	@Override
	public int compareTo(IRequestItems other){
		return this.getID()-other.getID();
	}
	
	@Override
	public int getID(){
		return this.itemID;
	}

	public Set<ItemIdentifier> getSpecificInterests() {
		return null;
	}

	public boolean hasGenericInterests() {
		return false;
	}
	
	public ISecurityProvider getSecurityProvider() {
		return SimpleServiceLocator.securityStationManager.getStation(getUpgradeManager().getSecurityID());
	}
	
	public boolean canBeDestroyedByPlayer(EntityPlayer entityPlayer) {
		LogisticsSecurityTileEntity station = SimpleServiceLocator.securityStationManager.getStation(getUpgradeManager().getSecurityID());
		if(station != null) {
			return station.getSecuritySettingsForPlayer(entityPlayer, true).removePipes;
		}
		return true;
	}
	
	public boolean canBeDestroyed() {
		ISecurityProvider sec = getSecurityProvider();
		if(sec != null) {
			if(!sec.canAutomatedDestroy()) {
				return false;
			}
		}
		return true;
	}

	public void setDestroyByPlayer() {
		destroyByPlayer = true;
	}
	
	public boolean blockRemove() {
		return blockRemove;
	}
	
	public void checkCCAccess() throws PermissionException {
		ISecurityProvider sec = getSecurityProvider();
		if(sec != null) {
			int id = -1;
			if(this.container instanceof LogisticsTileGenericPipe) {
				id = ((LogisticsTileGenericPipe)this.container).getLastCCID();
			}
			if(!sec.getAllowCC(id)) {
				throw new PermissionException();
			}
		}
	}

	public void queueUnroutedItemInformation(TravelingItem data) {
		if(data != null && data.getItemStack() != null) {
			data.setItemStack(data.getItemStack().copy());
			queuedDataForUnroutedItems.add(data);
		}
	}
	
	public TravelingItem getQueuedForItemStack(ItemStack stack) {
		for(TravelingItem item:queuedDataForUnroutedItems) {
			if(ItemIdentifierStack.getFromStack(item.getItemStack()).equals(ItemIdentifierStack.getFromStack(stack))) {
				queuedDataForUnroutedItems.remove(item);
				return item;
			}
		}
		return null;
	}

	/** used as a distance offset when deciding which pipe to use
	 * NOTE: called very regularly, returning a pre-calculated int is probably appropriate.
	 * @return
	 */
	public double getLoadFactor() {
		return 0.0;
	}

	public void notifyOfItemArival(RoutedEntityItem routedEntityItem) {
		this._inTransitToMe.remove(routedEntityItem);		
		if (this instanceof IRequireReliableTransport){
			((IRequireReliableTransport)this).itemArrived(ItemIdentifierStack.getFromStack(routedEntityItem.getItemStack()));
		}
		if (this instanceof IRequireReliableFluidTransport) {
			ItemStack stack = routedEntityItem.getItemStack();
			if(stack.getItem() instanceof LogisticsFluidContainer) {
				FluidStack liquid = SimpleServiceLocator.logisticsFluidManager.getFluidFromContainer(stack);
				((IRequireReliableFluidTransport)this).liquidArrived(FluidIdentifier.get(liquid), liquid.amount);				
			}
		}
	}

	public int countOnRoute(ItemIdentifier it) {
		int count = 0;
		for(Iterator<IRoutedItem> iter = _inTransitToMe.iterator();iter.hasNext();) {
			IRoutedItem next = iter.next();
			if(next.getIDStack().getItem() == it)
				count += next.getIDStack().getStackSize();
		}
		return count;
	}
	
	@SideOnly(Side.CLIENT)
	public LPPipeIconProvider getLPIconProvider() {
		return Textures.LPpipeIconProvider;
	}

	public final int getIconIndex(ForgeDirection connection) {
		TextureType texture = getTextureType(connection);
		if(_textureBufferPowered) {
			return texture.powered;
		} else if(Configs.LOGISTICS_POWER_USAGE_DISABLED) {
			return texture.normal;
		} else {
			return texture.unpowered;
		}
	}

	@Override
	public final int getX() {
		return this.container.xCoord;
	}

	@Override
	public final int getY() {
		return this.container.yCoord;
	}

	@Override
	public final int getZ() {
		return this.container.zCoord;
	}

	public void addCrashReport(CrashReportCategory crashReportCategory) {
		addRouterCrashReport(crashReportCategory);
	}
	
	protected void addRouterCrashReport(CrashReportCategory crashReportCategory) {
		crashReportCategory.addCrashSection("Router", this.getRouter().toString());
	}
	
	public boolean isFluidPipe() {
		return false;
	}
	
	/*
	// --- Trigger ---
	@Override
	public LinkedList<IAction> getActions() {
		LinkedList<IAction> actions = super.getActions();
		actions.add(BuildCraftProxy.LogisticsDisableAction);
		return actions;
	}
	
	@Override
	protected void actionsActivated(Map<IAction, Boolean> actions) {
		super.actionsActivated(actions);

		setEnabled(true);
		// Activate the actions
		for (Entry<IAction, Boolean> i : actions.entrySet()) {
			if (i.getValue()) {
				if (i.getKey() instanceof ActionDisableLogistics){
					setEnabled(false);
				}
			}
		}
	}
	*/
	
	/* --- CCCommands --- */
	@CCCommand(description="Returns the Router UUID as an integer; all pipes have a unique ID")
	public int getRouterId() {
		return getRouter().getSimpleID();
	}

	@CCCommand(description="Sets the TurtleConnect flag for this Turtle on this LogisticsPipe")
	public void setTurtleConnect(Boolean flag) {
		if(this.container instanceof LogisticsTileGenericPipe) {
			((LogisticsTileGenericPipe)this.container).setTurtleConnect(flag);
		}
	}

	@CCCommand(description="Returns the TurtleConnect flag for this Turtle on this LogisticsPipe")
	public boolean getTurtleConnect() {
		if(this.container instanceof LogisticsTileGenericPipe) {
			return ((LogisticsTileGenericPipe)this.container).getTurtleConnect();
		}
		return false;
	}

	@CCCommand(description="Returns the Item Id for given ItemIdentifier Id.")
	public int getItemID(Double itemId) throws Exception {
		if(itemId == null) throw new Exception("Invalid ItemIdentifierID");
		ItemIdentifier item = ItemIdentifier.getForId((int)Math.floor(itemId));
		if(item == null) throw new Exception("Invalid ItemIdentifierID");
		return item.itemID;
	}

	@CCCommand(description="Returns the Item damage for the given ItemIdentifier Id.")
	public int getItemDamage(Double itemId) throws Exception {
		if(itemId == null) throw new Exception("Invalid ItemIdentifierID");
		ItemIdentifier itemd = ItemIdentifier.getForId((int)Math.floor(itemId));
		if(itemd == null) throw new Exception("Invalid ItemIdentifierID");
		return itemd.itemDamage;
	}

	@CCCommand(description="Returns the NBTTagCompound for the given ItemIdentifier Id.")
	public Map<Object,Object> getNBTTagCompound(Double itemId) throws Exception {
		ItemIdentifier itemn = ItemIdentifier.getForId((int)Math.floor(itemId));
		if(itemn == null) throw new Exception("Invalid ItemIdentifierID");
		return itemn.getNBTTagCompoundAsMap();
	}

	@CCCommand(description="Returns the ItemIdentifier Id for the given Item id and damage.")
	public int getItemIdentifierIDFor(Double itemID, Double itemDamage) {
		return ItemIdentifier.get((int)Math.floor(itemID), (int)Math.floor(itemDamage), null).getId();
	}

	@CCCommand(description="Returns the name of the item for the given ItemIdentifier Id.")
	public String getUnlocalizedName(Double itemId) throws Exception {
		if(itemId == null) throw new Exception("Invalid ItemIdentifierID");
		ItemIdentifier itemd = ItemIdentifier.getForId((int)Math.floor(itemId));
		if(itemd == null) throw new Exception("Invalid ItemIdentifierID");
		return itemd.getFriendlyNameCC();
	}

	@CCCommand(description="Returns true if the computer is allowed to interact with the connected pipe.", needPermission=false)
	public boolean canAccess() {
		ISecurityProvider sec = getSecurityProvider();
		if(sec != null) {
			int id = -1;
			if(this.container instanceof LogisticsTileGenericPipe) {
				id = ((LogisticsTileGenericPipe)this.container).getLastCCID();
			}
			return sec.getAllowCC(id);
		}
		return true;
	}
	
	// from logic
	public void onWrenchClicked(EntityPlayer entityplayer) {
		if (MainProxy.isServer(entityplayer.worldObj)) {
			entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_Freq_Card_ID, getWorld(), getX(), getY(), getZ());
		}
	}
	
	final void destroy(){ // no overide, put code in OnBlockRemoval
	
	}

	public void setTile(LogisticsTileGenericPipe tile) {

		this.container = tile;

		transport.setTile(tile);
	}

	public void onBlockPlaced() {
		transport.onBlockPlaced();
	}

	public void onBlockPlacedBy(EntityLivingBase placer) {
	}

	private boolean initialized = false;

	public boolean needsInit() {
		return !initialized;
	}

	public void initialize() {
		transport.initialize();
		initialized = true;
	}

	public boolean inputOpen(ForgeDirection from) {
		return transport.inputOpen(from);
	}

	public boolean outputOpen(ForgeDirection to) {
		return transport.outputOpen(to);
	}

	public void onEntityCollidedWithBlock(Entity entity) {
	}

	public boolean canConnectRedstone() {
		if (hasGate())
			return true;

		return false;
	}

	public int isPoweringTo(int side) {
		if (gate != null && gate.isEmittingRedstone()) {
			ForgeDirection o = ForgeDirection.getOrientation(side).getOpposite();
			TileEntity tile = container.getTile(o);

			if (tile instanceof TileGenericPipe && container.isPipeConnected(o))
				return 0;

			return 15;
		}
		return 0;
	}

	public int isIndirectlyPoweringTo(int l) {
		return isPoweringTo(l);
	}

	public void randomDisplayTick(Random random) {
	}

	protected void notifyBlocksOfNeighborChange(ForgeDirection side) {
		container.worldObj.notifyBlocksOfNeighborChange(container.xCoord + side.offsetX, container.yCoord + side.offsetY, container.zCoord + side.offsetZ, BuildCraftTransport.genericPipeBlock.blockID);
	}

	protected void updateNeighbors(boolean needSelf) {
		if (needSelf) {
			container.worldObj.notifyBlocksOfNeighborChange(container.xCoord, container.yCoord, container.zCoord, BuildCraftTransport.genericPipeBlock.blockID);
		}
		for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
			notifyBlocksOfNeighborChange(side);
		}
	}

	public LogisticsTileGenericPipe getContainer() {
		return container;
	}

	public void onDropped(EntityItem item) {
	}

	/**
	 * If this pipe is open on one side, return it.
	 */
	public ForgeDirection getOpenOrientation() {
		int Connections_num = 0;

		ForgeDirection target_orientation = ForgeDirection.UNKNOWN;

		for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
			if (container.isPipeConnected(o)) {

				Connections_num++;

				if (Connections_num == 1)
					target_orientation = o;
			}
		}

		if (Connections_num > 1 || Connections_num == 0)
			return ForgeDirection.UNKNOWN;

		return target_orientation.getOpposite();
	}
}
