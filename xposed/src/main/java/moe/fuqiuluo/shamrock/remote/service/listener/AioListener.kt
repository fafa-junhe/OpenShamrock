@file:OptIn(DelicateCoroutinesApi::class)

package moe.fuqiuluo.shamrock.remote.service.listener

import moe.fuqiuluo.shamrock.helper.MessageHelper
import com.tencent.qqnt.kernel.nativeinterface.*
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.fuqiuluo.qqinterface.servlet.GroupSvc
import moe.fuqiuluo.qqinterface.servlet.MsgSvc
import moe.fuqiuluo.qqinterface.servlet.TicketSvc
import moe.fuqiuluo.qqinterface.servlet.msg.MessageTempHandler
import moe.fuqiuluo.qqinterface.servlet.msg.toCQCode
import moe.fuqiuluo.qqinterface.servlet.transfile.RichProtoSvc
import moe.fuqiuluo.shamrock.remote.service.config.ShamrockConfig
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.helper.db.MessageDB
import moe.fuqiuluo.shamrock.remote.service.api.GlobalEventTransmitter
import moe.fuqiuluo.qqinterface.servlet.transfile.RichMediaUploadHandler
import moe.fuqiuluo.shamrock.remote.service.data.push.MessageTempSource
import moe.fuqiuluo.shamrock.remote.service.data.push.PostType
import moe.fuqiuluo.shamrock.utils.PlatformUtils
import moe.fuqiuluo.shamrock.utils.PlatformUtils.QQ_9_0_8_VER
import java.util.ArrayList
import kotlin.collections.HashMap
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.os.BatteryManager
import android.content.Intent
import mqq.app.MobileQQ
import android.content.IntentFilter

internal object AioListener : IKernelMsgListener {
    override fun onRecvMsg(msgList: ArrayList<MsgRecord>) {
        if (msgList.isEmpty()) return

        GlobalScope.launch {
            msgList.forEach {
                handleMsg(it)
            }
        }
    }
    private fun getBatteryPercentage(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= 21) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, iFilter)
            
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            
            if (level != -1 && scale != -1) {
                val batteryPct = level.toDouble() / scale.toDouble()
                (batteryPct * 100).toInt()
            } else {
                -1 // Return -1 or some default value if level or scale is invalid
            }
        }
    }


    private suspend fun handleMsg(record: MsgRecord) {
        try {
            if (MessageTempHandler.notify(record)) return
            if (record.msgSeq < 0) return
            if (record.chatType == MsgConstant.KCHATTYPETEMPPUBLICACCOUNT) return // 订阅号不处理

            val msgHash = MessageHelper.generateMsgIdHash(record.chatType, record.msgId)

            val peerId = when (record.chatType) {
                MsgConstant.KCHATTYPEGUILD -> record.guildId
                else -> record.peerUin.toString()
            }
            MessageHelper.saveMsgMapping(
                hash = msgHash,
                qqMsgId = record.msgId,
                chatType = record.chatType,
                subChatType = record.chatType,
                peerId = peerId,
                msgSeq = record.msgSeq.toInt(),
                time = record.msgTime,
                subPeerId = record.channelId ?: peerId
            )

            val rawMsg = record.elements.toCQCode(record.chatType, peerId, record.channelId ?: peerId)
            if (rawMsg.isEmpty()) return

            if (ShamrockConfig.aliveReply() && rawMsg == "ping") {
                MessageHelper.sendMessageWithoutMsgId(record.chatType, record.peerUin.toString(), "pong" + getBatteryPercentage(MobileQQ.getContext()), { _, _ -> })
            } /*else if (ShamrockConfig.aliveReply() && rawMsg == ".shamrock.at_me") {
                MessageHelper.sendMessageWithoutMsgId(record.chatType, record.peerUin.toString(), "[CQ:at,qq=${record.senderUin}]", { _, _ -> })
            }*/


            val postType = if (record.senderUin == TicketSvc.getLongUin() && ShamrockConfig.enableSyncMsgAsSentMsg()) {
                PostType.MsgSent
            } else PostType.Msg

            //if (rawMsg.contains("forward")) {
            //    LogCenter.log(record.extInfoForUI.decodeToString(), Level.WARN)
            //}

            when (record.chatType) {
                MsgConstant.KCHATTYPEGROUP -> {
                    LogCenter.log("群消息(group = ${record.peerName}(${record.peerUin}), uin = ${record.senderUin}, id = $msgHash, seq = ${record.msgSeq}, msg = $rawMsg)")
                    ShamrockConfig.getGroupMsgRule()?.let { rule ->
                        if (!rule.black.isNullOrEmpty() && rule.black.contains(record.senderUin)) return
                        if (!rule.white.isNullOrEmpty() && !rule.white.contains(record.senderUin)) return
                    }

                    if (!GlobalEventTransmitter.MessageTransmitter.transGroupMessage(
                            record, record.elements, rawMsg, msgHash, postType
                        )
                    ) {
                        LogCenter.log("群消息推送失败 -> 推送目标可能不存在", Level.WARN)
                    }
                }

                MsgConstant.KCHATTYPEC2C -> {
                    LogCenter.log("私聊消息(private = ${record.senderUin}, id = [$msgHash | ${record.msgId} | ${record.msgSeq}], msg = $rawMsg)")
                    ShamrockConfig.getPrivateRule()?.let { rule ->
                        if (!rule.black.isNullOrEmpty() && rule.black.contains(record.senderUin)) return
                        if (!rule.white.isNullOrEmpty() && !rule.white.contains(record.senderUin)) return
                    }

                    if (!GlobalEventTransmitter.MessageTransmitter.transPrivateMessage(
                            record, record.elements, rawMsg, msgHash, postType
                        )
                    ) {
                        LogCenter.log("私聊消息推送失败 -> MessageTransmitter", Level.WARN)
                    }
                }

                MsgConstant.KCHATTYPETEMPC2CFROMGROUP -> {
                    if (!ShamrockConfig.allowTempSession()) return

                    ShamrockConfig.getPrivateRule()?.let { rule ->
                        if (!rule.black.isNullOrEmpty() && rule.black.contains(record.senderUin)) return
                        if (!rule.white.isNullOrEmpty() && !rule.white.contains(record.senderUin)) return
                    }

                    var groupCode = 0L
                    var fromNick = ""
                    MsgSvc.getTempChatInfo(record.chatType, record.senderUid).onSuccess {
                        groupCode = it.groupCode.toLong()
                        fromNick = it.fromNick
                    }

                    LogCenter.log("私聊临时消息(private = ${record.senderUin}, groupId=$groupCode, id = $msgHash, msg = $rawMsg)")

                    if (!GlobalEventTransmitter.MessageTransmitter.transPrivateMessage(
                            record,
                            record.elements,
                            rawMsg,
                            msgHash,
                            tempSource = MessageTempSource.Group,
                            postType = postType,
                            groupId = groupCode,
                            fromNick = fromNick
                        )
                    ) {
                        LogCenter.log("私聊临时消息推送失败 -> MessageTransmitter", Level.WARN)
                    }
                }

                MsgConstant.KCHATTYPEGUILD -> {
                    LogCenter.log("频道消息(guildId = ${record.guildId}, sender = ${record.senderUid}, id = [$msgHash | ${record.msgId}], msg = $rawMsg)")
                    if (!GlobalEventTransmitter.MessageTransmitter
                            .transGuildMessage(record, record.elements, rawMsg, msgHash, postType = postType)
                    ) {
                        LogCenter.log("频道消息推送失败 -> MessageTransmitter", Level.WARN)
                    }
                }

                else -> LogCenter.log("不支持PUSH事件: ${record.chatType}")
            }
        } catch (e: Throwable) {
            LogCenter.log(e.stackTraceToString(), Level.WARN)
        }
    }

    override fun onMsgRecall(chatType: Int, peerId: String, msgId: Long) {
        LogCenter.log("onMsgRecall($chatType, $peerId, $msgId)")
    }

    override fun onAddSendMsg(record: MsgRecord) {
        if (record.peerUin == TicketSvc.getLongUin()) return // 发给自己的消息不处理

        GlobalScope.launch {
            try {
                val msgHash = MessageHelper.generateMsgIdHash(record.chatType, record.msgId)
                val peerId = when (record.chatType) {
                    MsgConstant.KCHATTYPEGUILD -> record.guildId
                    else -> record.peerUin.toString()
                }
                MessageHelper.saveMsgMapping(
                    hash = msgHash,
                    qqMsgId = record.msgId,
                    chatType = record.chatType,
                    subChatType = record.chatType,
                    peerId = peerId,
                    msgSeq = record.msgSeq.toInt(),
                    time = record.msgTime,
                    subPeerId = record.channelId ?: peerId
                )

                LogCenter.log("预发送消息($msgHash | ${record.msgSeq} | ${record.msgId})")
            } catch (e: Throwable) {
                LogCenter.log(e.stackTraceToString(), Level.WARN)
            }
        }
    }

    override fun onMsgInfoListUpdate(msgList: ArrayList<MsgRecord>?) {
        msgList?.forEach { record ->
            if (record.sendStatus == MsgConstant.KSENDSTATUSFAILED
                || record.sendStatus == MsgConstant.KSENDSTATUSSENDING
            ) {
                return@forEach
            }

            GlobalScope.launch {
                val msgHash = MessageHelper.generateMsgIdHash(record.chatType, record.msgId)
                val peerId = when (record.chatType) {
                    MsgConstant.KCHATTYPEGUILD -> record.guildId
                    else -> record.peerUin.toString()
                }

                val mapping = MessageHelper.getMsgMappingByHash(msgHash)
                if (mapping == null) {
                    MessageHelper.saveMsgMapping(
                        hash = msgHash,
                        qqMsgId = record.msgId,
                        chatType = record.chatType,
                        subChatType = record.chatType,
                        peerId = peerId,
                        msgSeq = record.msgSeq.toInt(),
                        time = record.msgTime,
                        subPeerId = record.channelId ?: peerId
                    )
                } else {
                    LogCenter.log("Update message info from ${mapping.msgSeq} to ${record.msgSeq}", Level.INFO)
                    MessageDB.getInstance().messageMappingDao()
                        .updateMsgSeqByMsgHash(msgHash, record.msgSeq.toInt())
                }

                if (!ShamrockConfig.enableSelfMsg()
                    || record.senderUin != TicketSvc.getLongUin()
                    || record.peerUin == TicketSvc.getLongUin()
                ) return@launch

                val rawMsg = record.elements.toCQCode(record.chatType, peerId, record.channelId ?: peerId)
                if (rawMsg.isEmpty()) return@launch
                LogCenter.log("自发消息(target = ${record.peerUin}, id = $msgHash, msg = $rawMsg)")

                when (record.chatType) {
                    MsgConstant.KCHATTYPEGROUP -> {
                        if (!GlobalEventTransmitter.MessageTransmitter
                                .transGroupMessage(record, record.elements, rawMsg, msgHash, PostType.MsgSent)
                        ) {
                            LogCenter.log("自发群消息推送失败 -> MessageTransmitter", Level.WARN)
                        }
                    }

                    MsgConstant.KCHATTYPEC2C -> {
                        if (!GlobalEventTransmitter.MessageTransmitter
                                .transPrivateMessage(record, record.elements, rawMsg, msgHash, PostType.MsgSent)
                        ) {
                            LogCenter.log("自发私聊消息推送失败 -> MessageTransmitter", Level.WARN)
                        }
                    }

                    MsgConstant.KCHATTYPETEMPC2CFROMGROUP -> {
                        if (!ShamrockConfig.allowTempSession()) return@launch
                        if (!GlobalEventTransmitter.MessageTransmitter
                                .transPrivateMessage(
                                    record,
                                    record.elements,
                                    rawMsg,
                                    msgHash,
                                    PostType.MsgSent,
                                    MessageTempSource.Group
                                )
                        ) {
                            LogCenter.log("自发私聊临时消息推送失败 -> MessageTransmitter", Level.WARN)
                        }
                    }

                    else -> LogCenter.log("不支持SELF PUSH事件: ${record.chatType}")
                }
            }
        }
    }

    override fun onTempChatInfoUpdate(tempChatInfo: TempChatInfo) {

    }

    override fun onMsgAbstractUpdate(arrayList: ArrayList<MsgAbstract>?) {
        //arrayList?.forEach {
        //    LogCenter.log("onMsgAbstractUpdate($it)", Level.WARN)
        //}
    }

    override fun onRecvMsgSvrRspTransInfo(
        j2: Long,
        contact: Contact?,
        i2: Int,
        i3: Int,
        str: String?,
        bArr: ByteArray?
    ) {
        LogCenter.log("onRecvMsgSvrRspTransInfo($j2, $contact, $i2, $i3, $str)", Level.DEBUG)
    }

    override fun onRecvS2CMsg(arrayList: ArrayList<Byte>?) {
        LogCenter.log("onRecvS2CMsg(${arrayList.toString()})", Level.DEBUG)
    }

    override fun onRecvSysMsg(arrayList: ArrayList<Byte>?) {
        LogCenter.log("onRecvSysMsg(${arrayList.toString()})", Level.DEBUG)
    }

    override fun onBroadcastHelperDownloadComplete(broadcastHelperTransNotifyInfo: BroadcastHelperTransNotifyInfo?) {}

    override fun onBroadcastHelperProgerssUpdate(broadcastHelperTransNotifyInfo: BroadcastHelperTransNotifyInfo?) {}

    override fun onChannelFreqLimitInfoUpdate(
        contact: Contact?,
        z: Boolean,
        freqLimitInfo: FreqLimitInfo?
    ) {

    }

    override fun onContactUnreadCntUpdate(unreadMap: HashMap<Int, HashMap<String, UnreadCntInfo>>) {
        // 推送未读消息数量
    }

    override fun onCustomWithdrawConfigUpdate(customWithdrawConfig: CustomWithdrawConfig?) {
        LogCenter.log("onCustomWithdrawConfigUpdate: " + customWithdrawConfig.toString(), Level.DEBUG)
    }

    override fun onDraftUpdate(contact: Contact?, arrayList: ArrayList<MsgElement>?, j2: Long) {
        LogCenter.log("onDraftUpdate: " + contact.toString() + "|" + arrayList + "|" + j2.toString(), Level.DEBUG)
    }

    override fun onEmojiDownloadComplete(emojiNotifyInfo: EmojiNotifyInfo?) {

    }

    override fun onEmojiResourceUpdate(emojiResourceInfo: EmojiResourceInfo?) {

    }

    override fun onFeedEventUpdate(firstViewDirectMsgNotifyInfo: FirstViewDirectMsgNotifyInfo?) {

    }

    override fun onFirstViewDirectMsgUpdate(firstViewDirectMsgNotifyInfo: FirstViewDirectMsgNotifyInfo?) {

    }

    override fun onFirstViewGroupGuildMapping(arrayList: ArrayList<FirstViewGroupGuildInfo>?) {

    }

    override fun onGrabPasswordRedBag(
        i2: Int,
        str: String?,
        i3: Int,
        recvdOrder: RecvdOrder?,
        msgRecord: MsgRecord?
    ) {

    }

    override fun onKickedOffLine(kickedInfo: KickedInfo?) {
        LogCenter.log("onKickedOffLine($kickedInfo)")
    }

    override fun onFileMsgCome(arrayList: ArrayList<MsgRecord>?) {
        arrayList?.forEach { record ->
            GlobalScope.launch {
                when (record.chatType) {
                    MsgConstant.KCHATTYPEGROUP -> onGroupFileMsg(record)
                    MsgConstant.KCHATTYPEC2C -> onC2CFileMsg(record)
                    else -> LogCenter.log("不支持该来源的文件上传事件：${record}", Level.WARN)
                }
            }
        }
    }

    private suspend fun onC2CFileMsg(record: MsgRecord) {
        val userId = record.senderUin
        val fileMsg = record.elements.firstOrNull {
            it.elementType == MsgConstant.KELEMTYPEFILE
        }?.fileElement ?: kotlin.run {
            LogCenter.log("消息为私聊文件消息但不包含文件消息，来自：${record.peerUin}", Level.WARN)
            return
        }

        val fileName = fileMsg.fileName
        val fileSize = fileMsg.fileSize
        val expireTime = fileMsg.expireTime ?: 0
        val fileId = fileMsg.fileUuid
        val fileSubId = fileMsg.fileSubId ?: ""
        val url = RichProtoSvc.getC2CFileDownUrl(fileId, fileSubId)

        if (!GlobalEventTransmitter.FileNoticeTransmitter
                .transPrivateFileEvent(record.msgTime, userId, fileId, fileSubId, fileName, fileSize, expireTime, url)
        ) {
            LogCenter.log("私聊文件消息推送失败 -> FileNoticeTransmitter", Level.WARN)
        }
    }

    private suspend fun onGroupFileMsg(record: MsgRecord) {
        val groupId = record.peerUin
        val userId = record.senderUin
        val fileMsg = record.elements.firstOrNull {
            it.elementType == MsgConstant.KELEMTYPEFILE
        }?.fileElement ?: kotlin.run {
            LogCenter.log("消息为群聊文件消息但不包含文件消息，来自：${record.peerUin}", Level.WARN)
            return
        }
        //val fileMd5 = fileMsg.fileMd5
        val fileName = fileMsg.fileName
        val fileSize = fileMsg.fileSize
        val uuid = fileMsg.fileUuid
        val bizId = fileMsg.fileBizId

        val url = RichProtoSvc.getGroupFileDownUrl(record.peerUin, uuid, bizId)

        if (!GlobalEventTransmitter.FileNoticeTransmitter
                .transGroupFileEvent(record.msgTime, userId, groupId, uuid, fileName, fileSize, bizId, url)
        ) {
            LogCenter.log("群聊文件消息推送失败 -> FileNoticeTransmitter", Level.WARN)
        }
    }

    override fun onRichMediaUploadComplete(notifyInfo: FileTransNotifyInfo) {
        LogCenter.log({ "[BDH] 资源上传完成(${notifyInfo.trasferStatus}, ${notifyInfo.fileId}, ${notifyInfo.msgId}, ${notifyInfo.commonFileInfo})" }, Level.DEBUG)
        RichMediaUploadHandler.notify(notifyInfo)
    }

    override fun onRecvOnlineFileMsg(arrayList: ArrayList<MsgRecord>?) {
        LogCenter.log(("onRecvOnlineFileMsg" + arrayList?.joinToString { ", " }), Level.DEBUG)
    }

    override fun onRichMediaDownloadComplete(fileTransNotifyInfo: FileTransNotifyInfo) {

    }

    override fun onRichMediaProgerssUpdate(fileTransNotifyInfo: FileTransNotifyInfo) {

    }

    override fun onSearchGroupFileInfoUpdate(searchGroupFileResult: SearchGroupFileResult?) {
        LogCenter.log("onSearchGroupFileInfoUpdate($searchGroupFileResult)", Level.DEBUG)
    }

    override fun onGroupFileInfoAdd(groupItem: GroupItem?) {
        LogCenter.log("onGroupFileInfoAdd: " + groupItem.toString(), Level.DEBUG)
    }

    override fun onGroupFileInfoUpdate(groupFileListResult: GroupFileListResult?) {
        LogCenter.log("onGroupFileInfoUpdate: " + groupFileListResult.toString(), Level.DEBUG)
    }

    override fun onGroupGuildUpdate(groupGuildNotifyInfo: GroupGuildNotifyInfo?) {
        LogCenter.log("onGroupGuildUpdate: " + groupGuildNotifyInfo.toString(), Level.DEBUG)
    }

    override fun onGroupTransferInfoAdd(groupItem: GroupItem?) {
        LogCenter.log("onGroupTransferInfoAdd: " + groupItem.toString(), Level.DEBUG)
    }

    override fun onGroupTransferInfoUpdate(groupFileListResult: GroupFileListResult?) {
        LogCenter.log("onGroupTransferInfoUpdate: " + groupFileListResult.toString(), Level.DEBUG)
    }

    override fun onGuildInteractiveUpdate(guildInteractiveNotificationItem: GuildInteractiveNotificationItem?) {

    }

    override fun onGuildMsgAbFlagChanged(guildMsgAbFlag: GuildMsgAbFlag?) {

    }

    override fun onGuildNotificationAbstractUpdate(guildNotificationAbstractInfo: GuildNotificationAbstractInfo?) {

    }

    override fun onHitCsRelatedEmojiResult(downloadRelateEmojiResultInfo: DownloadRelateEmojiResultInfo?) {

    }

    override fun onHitEmojiKeywordResult(hitRelatedEmojiWordsResult: HitRelatedEmojiWordsResult?) {

    }

    override fun onHitRelatedEmojiResult(relatedWordEmojiInfo: RelatedWordEmojiInfo?) {

    }

    override fun onImportOldDbProgressUpdate(importOldDbMsgNotifyInfo: ImportOldDbMsgNotifyInfo?) {

    }

    override fun onInputStatusPush(inputStatusInfo: InputStatusInfo?) {

    }

    override fun onLineDev(devList: ArrayList<DevInfo>?) {
        //LogCenter.log("onLineDev($arrayList)")
    }

    override fun onLogLevelChanged(newLevel: Long) {

    }

    override fun onMsgBoxChanged(arrayList: ArrayList<ContactMsgBoxInfo>?) {

    }

    override fun onMsgDelete(contact: Contact?, arrayList: ArrayList<Long>?) {

    }

    override fun onMsgEventListUpdate(hashMap: HashMap<String, ArrayList<Long>>?) {

    }

    override fun onMsgInfoListAdd(arrayList: ArrayList<MsgRecord>?) {

    }

    override fun onMsgQRCodeStatusChanged(i2: Int) {

    }

    override fun onMsgSecurityNotify(msgRecord: MsgRecord?) {
        LogCenter.log("onMsgSecurityNotify($msgRecord)")
    }

    override fun onMsgSettingUpdate(msgSetting: MsgSetting?) {

    }

    override fun onNtFirstViewMsgSyncEnd() {

    }

    override fun onNtMsgSyncEnd() {
        LogCenter.log("NTKernel同步消息完成", Level.DEBUG)
    }

    override fun onNtMsgSyncStart() {
        LogCenter.log("NTKernel同步消息开始", Level.DEBUG)
    }

    override fun onReadFeedEventUpdate(firstViewDirectMsgNotifyInfo: FirstViewDirectMsgNotifyInfo?) {

    }

    override fun onRecvGroupGuildFlag(i2: Int) {

    }

    override fun onRecvUDCFlag(i2: Int) {
        LogCenter.log("onRecvUDCFlag($i2)", Level.DEBUG)
    }

    override fun onSendMsgError(j2: Long, contact: Contact?, i2: Int, str: String?) {
        LogCenter.log("onSendMsgError($j2, $contact, $j2, $str)", Level.DEBUG)
    }

    override fun onSysMsgNotification(i2: Int, j2: Long, j3: Long, z: Boolean, arrayList: ArrayList<Byte>?) {
        LogCenter.log("onSysMsgNotification($i2, $j2, $j3, $z, $arrayList)", Level.DEBUG)
    }

    override fun onUnreadCntAfterFirstView(hashMap: HashMap<Int, ArrayList<UnreadCntInfo>>?) {

    }

    override fun onUnreadCntUpdate(hashMap: HashMap<Int, ArrayList<UnreadCntInfo>>?) {

    }

    override fun onUserChannelTabStatusChanged(z: Boolean) {

    }

    override fun onUserOnlineStatusChanged(z: Boolean) {

    }

    override fun onUserTabStatusChanged(arrayList: ArrayList<TabStatusInfo>?) {
        LogCenter.log("onUserTabStatusChanged($arrayList)", Level.DEBUG)
    }

    override fun onlineStatusBigIconDownloadPush(i2: Int, j2: Long, str: String?) {

    }

    override fun onlineStatusSmallIconDownloadPush(i2: Int, j2: Long, str: String?) {

    }
}
